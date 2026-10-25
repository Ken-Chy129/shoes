package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import cn.ken.shoes.model.excel.EbayPoisonListingExcel;
import cn.ken.shoes.util.ShoesUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eBay 得物价上架：Excel 只填货号，查询得物价格后把有价的尺码全部上架。
 * 上架价(USD) = 得物价(CNY) × 1.25 ÷ 6.6 + 50，保留两位小数。
 * 实际上架复用批量上架流程（同一货号合并为一条多尺码商品，SKU 与批量上架一致）。
 */
@Slf4j
@Service
public class EbayPoisonListingService {

    public static final String TASK_TYPE = "ebay_poison_listing";
    public static final int MAX_STYLES = 2000;
    public static final int LISTING_QUANTITY = 1;
    static final BigDecimal PRICE_MULTIPLIER = new BigDecimal("1.25");
    static final BigDecimal EXCHANGE_RATE = new BigDecimal("6.6");
    static final BigDecimal PRICE_ADDITION = new BigDecimal("50");

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final TaskInputSnapshotStore snapshotStore;
    private final PoisonClient poisonClient;
    private final EbayBulkListingService bulkListingService;
    private final EbayListingFactory listingFactory;
    private final EbayProperties properties;
    private final Executor executor;
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    @Autowired
    public EbayPoisonListingService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                                    TaskInputSnapshotStore snapshotStore, PoisonClient poisonClient,
                                    EbayBulkListingService bulkListingService,
                                    EbayListingFactory listingFactory, EbayProperties properties) {
        this(taskMapper, taskItemMapper, snapshotStore, poisonClient, bulkListingService, listingFactory,
                properties, command -> Thread.ofVirtual().name("Ebay-Poison-Listing").start(command));
    }

    EbayPoisonListingService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                             TaskInputSnapshotStore snapshotStore, PoisonClient poisonClient,
                             EbayBulkListingService bulkListingService, EbayListingFactory listingFactory,
                             EbayProperties properties, Executor executor) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.snapshotStore = snapshotStore;
        this.poisonClient = poisonClient;
        this.bulkListingService = bulkListingService;
        this.listingFactory = listingFactory;
        this.properties = properties;
        this.executor = executor;
    }

    /** 得物价(CNY) × 1.25 ÷ 6.6 + 50，四舍五入到分。 */
    public static BigDecimal listingPrice(BigDecimal poisonPrice) {
        return poisonPrice.multiply(PRICE_MULTIPLIER)
                .divide(EXCHANGE_RATE, 10, RoundingMode.HALF_UP)
                .add(PRICE_ADDITION)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public Long start(List<EbayPoisonListingExcel> rows, EbayListingRegion region) {
        EbayListingRegion effectiveRegion = region == null ? EbayListingRegion.HK : region;
        if (!properties.isRegionConfigured(effectiveRegion)) {
            throw new IllegalArgumentException(effectiveRegion.getLabel()
                    + "上架未配置发货地点或运输政策，请联系管理员配置后再试");
        }
        List<String> styleIds = normalize(rows);
        EbayProperties.ListingSettings settings = properties.listingSettings(effectiveRegion);
        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("modelNoCount", styleIds.size())
                .fluentPut("priceFormula", "得物价×1.25÷6.6+50")
                .fluentPut("quantity", LISTING_QUANTITY)
                .fluentPut("currency", properties.getDefaultCurrency())
                .fluentPut("region", effectiveRegion.getCode())
                .fluentPut("merchantLocationKey", settings.merchantLocationKey())
                .fluentPut("fulfillmentPolicyId", settings.fulfillmentPolicyId())
                .toJSONString());
        taskMapper.insert(task);
        Long taskId = task.getId();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(taskId, cancelled);
        try {
            snapshotStore.saveEbayPoisonListingInput(taskId, styleIds.stream().map(styleId -> {
                EbayPoisonListingExcel row = new EbayPoisonListingExcel();
                row.setStyleId(styleId);
                return row;
            }).toList());
            executor.execute(() -> run(taskId, styleIds, cancelled, effectiveRegion));
            return taskId;
        } catch (RuntimeException e) {
            running.remove(taskId);
            taskMapper.updateTaskFailed(taskId, "任务输入保存或启动失败");
            throw e;
        }
    }

    public void cancel(Long taskId) {
        AtomicBoolean cancelled = running.get(taskId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    void run(Long taskId, List<String> styleIds, AtomicBoolean cancelled, EbayListingRegion region) {
        try {
            List<PoisonPriceDO> prices;
            try {
                prices = poisonClient.batchQueryPrice(styleIds);
            } catch (RuntimeException e) {
                log.error("eBay得物价上架查询得物价格失败, taskId:{}", taskId, e);
                taskMapper.updateTaskFailed(taskId, "得物查价失败，请稍后重试");
                return;
            }
            if (cancelled.get()) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                return;
            }
            Map<String, Map<String, BigDecimal>> byStyle = groupPrices(prices);
            List<EbayListingExcel> listingRows = new ArrayList<>();
            Map<EbayListingExcel, BigDecimal> poisonPrices = new java.util.IdentityHashMap<>();
            int noPriceStyles = 0;
            for (String styleId : styleIds) {
                Map<String, BigDecimal> sizes = byStyle.getOrDefault(styleId.toUpperCase(Locale.ROOT), Map.of());
                int listed = 0;
                for (Map.Entry<String, BigDecimal> size : sizes.entrySet()) {
                    EbayListingExcel row = new EbayListingExcel();
                    row.setStyleId(styleId);
                    row.setSize("EU" + size.getKey());
                    row.setQuantity(LISTING_QUANTITY);
                    row.setPrice(listingPrice(size.getValue()));
                    try {
                        listingFactory.parseSize(row.getSize());
                    } catch (IllegalArgumentException e) {
                        log.info("eBay得物价上架跳过无法识别的得物尺码, styleId:{}, size:{}", styleId, size.getKey());
                        continue;
                    }
                    listingRows.add(row);
                    poisonPrices.put(row, size.getValue());
                    listed++;
                }
                if (listed == 0) {
                    noPriceStyles++;
                    recordNoPrice(taskId, styleId);
                }
            }
            JSONObject extra = new JSONObject(true)
                    .fluentPut("styles", styleIds.size())
                    .fluentPut("noPriceStyles", noPriceStyles);
            if (listingRows.isEmpty()) {
                taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                        .fluentPut("total", 0).fluentPut("succeeded", 0).fluentPut("failed", 0)
                        .fluentPut("remaining", 0).fluentPutAll(extra).toJSONString());
                taskMapper.updateTaskFailed(taskId, "所有货号在得物都没有价格，未上架任何尺码");
                return;
            }
            bulkListingService.run(taskId, listingRows, cancelled, region,
                    row -> poisonPrices.get(row), extra);
        } catch (RuntimeException e) {
            log.error("eBay得物价上架任务异常, taskId:{}", taskId, e);
            taskMapper.updateTaskFailed(taskId, "任务异常终止，请查看任务明细");
        } finally {
            running.remove(taskId);
        }
    }

    /** 货号(大写) → EU码 → 得物价；同一尺码多条时取第一条有效价格。 */
    private Map<String, Map<String, BigDecimal>> groupPrices(List<PoisonPriceDO> prices) {
        Map<String, Map<String, BigDecimal>> result = new HashMap<>();
        if (prices == null) {
            return result;
        }
        for (PoisonPriceDO price : prices) {
            if (price == null || price.getModelNo() == null || price.getEuSize() == null
                    || price.getPrice() == null || price.getPrice() <= 0) {
                continue;
            }
            String euSize = ShoesUtil.getShoesSizeFrom(ShoesUtil.normalizeUnicodeFraction(price.getEuSize()));
            if (euSize == null) {
                continue;
            }
            result.computeIfAbsent(price.getModelNo().trim().toUpperCase(Locale.ROOT),
                            ignored -> new LinkedHashMap<>())
                    .putIfAbsent(euSize, BigDecimal.valueOf(price.getPrice()));
        }
        return result;
    }

    private void recordNoPrice(Long taskId, String styleId) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setStyleId(styleId);
        item.setCurrencyCode(properties.getDefaultCurrency());
        item.setOperateResult("未上架(得物无价)");
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    static List<String> normalize(List<EbayPoisonListingExcel> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> styleIds = new ArrayList<>();
        if (rows != null) {
            for (EbayPoisonListingExcel row : rows) {
                String styleId = row == null || row.getStyleId() == null ? null : row.getStyleId().trim();
                if (styleId == null || styleId.isEmpty()) {
                    continue;
                }
                if (seen.add(styleId.toUpperCase(Locale.ROOT))) {
                    styleIds.add(styleId);
                }
            }
        }
        if (styleIds.isEmpty()) {
            throw new IllegalArgumentException("Excel中没有货号");
        }
        if (styleIds.size() > MAX_STYLES) {
            throw new IllegalArgumentException("单次最多" + MAX_STYLES + "个货号");
        }
        return List.copyOf(styleIds);
    }
}
