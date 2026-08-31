package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.util.ShoesUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eBay 持续改价任务。任务本身保持 running，取消后才结束；每一轮都会在
 * task_item 留一份审计明细，避免覆盖批量上架任务的原始映射。
 */
@Slf4j
@Service
public class EbayPriceSyncService {

    public static final String TASK_TYPE = "ebay_price_sync";
    private static final long MIN_INTERVAL_HOURS = 1;
    private static final long MAX_INTERVAL_HOURS = 168;
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.01");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("100");

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final EbaySellApiClient ebayClient;
    private final PoisonClient poisonClient;
    private final EbayProperties properties;
    private final Map<Long, RunHandle> running = new ConcurrentHashMap<>();

    public EbayPriceSyncService(TaskMapper taskMapper,
                                TaskItemMapper taskItemMapper,
                                EbaySellApiClient ebayClient,
                                PoisonClient poisonClient,
                                EbayProperties properties) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.ebayClient = ebayClient;
        this.poisonClient = poisonClient;
        this.properties = properties;
    }

    public synchronized Long start(long intervalHours, BigDecimal priceMultiplier) {
        validate(intervalHours, priceMultiplier);
        TaskDO existing = taskMapper.selectRunningTask(
                "ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode());
        if (existing != null || !running.isEmpty()) {
            return null;
        }

        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("intervalHours", intervalHours)
                .fluentPut("priceMultiplier", priceMultiplier)
                .fluentPut("marketplaceId", properties.getDefaultMarketplaceId())
                .toJSONString());
        taskMapper.insert(task);
        RunHandle handle = new RunHandle();
        running.put(task.getId(), handle);
        Thread thread = Thread.ofVirtual().name("Ebay-Price-Sync-" + task.getId()).start(
                () -> runLoop(task.getId(), intervalHours, priceMultiplier, handle));
        handle.thread = thread;
        return task.getId();
    }

    public void cancel(Long taskId) {
        RunHandle handle = running.get(taskId);
        if (handle != null) {
            handle.cancelled.set(true);
            Thread thread = handle.thread;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    public boolean canRun() {
        return running.isEmpty()
                && taskMapper.selectRunningTask(
                "ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode()) == null;
    }

    void runSingleRound(Long taskId, BigDecimal priceMultiplier) {
        runSingleRound(taskId, priceMultiplier, 0);
    }

    void runSingleRound(Long taskId, BigDecimal priceMultiplier, int round) {
        List<TaskItemDO> mappings = taskItemMapper.selectEbayListingMappings();
        List<JSONObject> offers = ebayClient.getActiveOffers(properties.getDefaultMarketplaceId());
        Map<String, TaskItemDO> byOfferId = new HashMap<>();
        Map<String, TaskItemDO> bySku = new HashMap<>();
        for (TaskItemDO mapping : mappings == null ? List.<TaskItemDO>of() : mappings) {
            if (mapping.getOfferId() != null) {
                byOfferId.putIfAbsent(mapping.getOfferId(), mapping);
            }
            if (mapping.getSku() != null) {
                bySku.putIfAbsent(mapping.getSku(), mapping);
            }
        }

        List<OfferContext> contexts = new ArrayList<>();
        Set<String> modelNos = new HashSet<>();
        int skipped = 0;
        for (JSONObject offer : offers) {
            TaskItemDO mapping = mappingFor(offer, byOfferId, bySku);
            if (mapping == null || mapping.getStyleId() == null) {
                skipped++;
                continue;
            }
            String euSize = firstNonBlank(mapping.getEuSize(), euSizeFrom(mapping.getSize()));
            if (euSize == null) {
                skipped++;
                continue;
            }
            OfferContext context = new OfferContext(offer, mapping, euSize,
                    decimalAt(offer, "pricingSummary", "price", "value"),
                    integerAt(offer, "availableQuantity"));
            contexts.add(context);
            modelNos.add(mapping.getStyleId().trim());
        }

        if (contexts.isEmpty()) {
            taskMapper.updateTaskAttributes(taskId, attributes(0, 0, 0, skipped).toJSONString());
            return;
        }

        /*
         * An empty result for a non-empty batch is deliberately treated as a
         * technical/ambiguous response. This prevents a transient upstream
         * failure from setting every active eBay offer to zero inventory.
         * Individual missing model/size entries are still confirmed no-price
         * results when the batch contains other valid prices.
         */
        List<PoisonPriceDO> poisonPrices = poisonClient.batchQueryPrice(new ArrayList<>(modelNos));
        if (poisonPrices == null || poisonPrices.isEmpty()) {
            taskMapper.updateTaskFailReason(taskId, "本轮得物查价未返回可确认结果，已跳过改价和清库存");
            taskMapper.updateTaskAttributes(taskId, attributes(contexts.size(), 0, 0, skipped)
                    .fluentPut("skippedForPoisonFailure", contexts.size()).toJSONString());
            return;
        }

        Map<String, BigDecimal> prices = new HashMap<>();
        for (PoisonPriceDO price : poisonPrices) {
            if (price == null || price.getModelNo() == null || price.getEuSize() == null
                    || price.getPrice() == null || price.getPrice() <= 0) {
                continue;
            }
            prices.putIfAbsent(priceKey(price.getModelNo(), price.getEuSize()),
                    BigDecimal.valueOf(price.getPrice()));
        }

        int changed = 0;
        int noPrice = 0;
        int failed = 0;
        for (OfferContext context : contexts) {
            BigDecimal poisonPrice = prices.get(priceKey(context.mapping().getStyleId(), context.euSize()));
            BigDecimal target = poisonPrice == null ? null : targetPrice(poisonPrice, priceMultiplier);
            int targetQuantity = poisonPrice == null ? 0 : Math.max(context.quantity(), 0);
            String result;
            try {
                JSONObject payload = editableOfferPayload(context.offer());
                if (target != null) {
                    setPrice(payload, target);
                    setAvailableQuantity(payload, targetQuantity);
                    ebayClient.updateOffer(context.offerId(), payload, properties.getDefaultContentLanguage());
                    changed++;
                    result = "改价成功($" + target.toPlainString() + ")";
                } else {
                    setAvailableQuantity(payload, 0);
                    ebayClient.updateOffer(context.offerId(), payload, properties.getDefaultContentLanguage());
                    noPrice++;
                    result = "无得物价格，库存置0";
                }
            } catch (Exception e) {
                failed++;
                result = "改价失败(" + safeError(e) + ")";
            }
            recordItem(taskId, round, context, poisonPrice, target, targetQuantity, result);
        }
        taskMapper.updateTaskFailReason(taskId, failed == 0 ? null : "本轮有 " + failed + " 个商品处理失败");
        taskMapper.updateTaskAttributes(taskId, attributes(contexts.size(), changed, noPrice, skipped)
                .fluentPut("failed", failed).toJSONString());
    }

    private void runLoop(Long taskId, long intervalHours, BigDecimal multiplier, RunHandle handle) {
        int round = 0;
        try {
            while (!handle.cancelled.get()) {
                round++;
                try {
                    runSingleRound(taskId, multiplier, round);
                    taskMapper.updateTaskRound(taskId, round);
                } catch (Exception e) {
                    log.error("eBay定时改价第{}轮失败，保留现有库存和价格, taskId={}", round, taskId, e);
                    taskMapper.updateTaskFailReason(taskId, "第" + round + "轮失败：" + safeError(e));
                    taskMapper.updateTaskAttributes(taskId, attributes(0, 0, 0, 0)
                            .fluentPut("roundFailed", true).toJSONString());
                }
                if (handle.cancelled.get()) {
                    break;
                }
                Thread.sleep(intervalHours * 60L * 60L * 1000L);
            }
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        } finally {
            running.remove(taskId);
        }
    }

    private void recordItem(Long taskId, int round, OfferContext context, BigDecimal poisonPrice,
                            BigDecimal target, int quantity, String result) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(round);
        item.setTitle(context.mapping().getTitle());
        item.setListingId(context.mapping().getListingId());
        item.setSku(context.mapping().getSku());
        item.setOfferId(context.offerId());
        item.setStyleId(context.mapping().getStyleId());
        item.setSize(context.mapping().getSize());
        item.setEuSize(context.euSize());
        item.setCurrentPrice(context.currentPrice());
        item.setTargetPrice(target);
        item.setListingQuantity(quantity);
        item.setPoisonPrice(poisonPrice);
        item.setOperateResult(result);
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    private TaskItemDO mappingFor(JSONObject offer, Map<String, TaskItemDO> byOfferId,
                                  Map<String, TaskItemDO> bySku) {
        String offerId = offer.getString("offerId");
        TaskItemDO mapping = offerId == null ? null : byOfferId.get(offerId);
        return mapping != null ? mapping : bySku.get(offer.getString("sku"));
    }

    private JSONObject editableOfferPayload(JSONObject offer) {
        JSONObject payload = JSONObject.parseObject(offer.toJSONString());
        for (String field : List.of("offerId", "status", "listing", "listingId",
                "createdDate", "lastModifiedDate", "marketplace", "listingStatus")) {
            payload.remove(field);
        }
        return payload;
    }

    private void setPrice(JSONObject payload, BigDecimal target) {
        JSONObject summary = payload.getJSONObject("pricingSummary");
        if (summary == null) {
            summary = new JSONObject(true);
            payload.put("pricingSummary", summary);
        }
        JSONObject price = summary.getJSONObject("price");
        if (price == null) {
            price = new JSONObject(true);
            summary.put("price", price);
        }
        price.put("currency", properties.getDefaultCurrency());
        price.put("value", target.toPlainString());
    }

    private void setAvailableQuantity(JSONObject payload, int quantity) {
        payload.put("availableQuantity", quantity);
    }

    private BigDecimal targetPrice(BigDecimal poisonPrice, BigDecimal multiplier) {
        double exchangeRate = PriceSwitch.EXCHANGE_RATE == null ? 0D : PriceSwitch.EXCHANGE_RATE;
        if (exchangeRate <= 0D) {
            throw new IllegalStateException("汇率配置无效");
        }
        BigDecimal target = poisonPrice.multiply(multiplier)
                .divide(BigDecimal.valueOf(exchangeRate), 2, RoundingMode.HALF_UP);
        return target.max(new BigDecimal("0.01"));
    }

    private JSONObject attributes(int total, int changed, int noPrice, int skipped) {
        return new JSONObject(true)
                .fluentPut("total", total)
                .fluentPut("changed", changed)
                .fluentPut("noPrice", noPrice)
                .fluentPut("skipped", skipped);
    }

    private String euSizeFrom(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        return ShoesUtil.getShoesSizeFrom(
                ShoesUtil.normalizeUnicodeFraction(size).toUpperCase(Locale.ROOT));
    }

    private BigDecimal decimalAt(JSONObject object, String... path) {
        Object current = object;
        for (String part : path) {
            if (!(current instanceof JSONObject json)) {
                return null;
            }
            current = json.get(part);
        }
        if (current == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(current));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int integerAt(JSONObject object, String key) {
        Integer value = object.getInteger(key);
        return value == null ? 0 : Math.max(value, 0);
    }

    private String priceKey(String modelNo, String euSize) {
        return modelNo.trim().toUpperCase(Locale.ROOT) + ":" + ShoesUtil.normalizeUnicodeFraction(euSize);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "系统异常";
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    private void validate(long intervalHours, BigDecimal multiplier) {
        if (intervalHours < MIN_INTERVAL_HOURS || intervalHours > MAX_INTERVAL_HOURS) {
            throw new IllegalArgumentException("执行间隔必须是1到168小时");
        }
        if (multiplier == null || multiplier.compareTo(MIN_MULTIPLIER) < 0
                || multiplier.compareTo(MAX_MULTIPLIER) > 0) {
            throw new IllegalArgumentException("得物价格系数必须在0.01到100之间");
        }
    }

    private record OfferContext(JSONObject offer, TaskItemDO mapping, String euSize,
                                BigDecimal currentPrice, int quantity) {
        String offerId() {
            return offer.getString("offerId");
        }
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread thread;

        private RunHandle() {}
    }
}
