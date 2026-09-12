package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eBay 下架任务。结束在架 listing，但保留 offer 与库存数据，
 * 因此同一批商品之后可以直接重新上架，无需重建 SKU。
 *
 * <p>下架范围以 eBay 账号上的真实在架 offer 为准，而不是本地任务记录：
 * 历史上有些 listing 的映射没有落库（例如上架成功但任务明细写入失败），
 * 只按本地记录下架会漏掉它们。本地映射仅用于补齐货号、标题等展示信息，
 * 以及支持按货号过滤。
 */
@Slf4j
@Service
public class EbayDelistService {

    public static final String TASK_TYPE = "ebay_delist";
    public static final String ZERO_STOCK_TASK_TYPE = "ebay_zero_stock";

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final EbaySellApiClient ebayClient;
    private final EbayProperties properties;
    private final Executor executor;
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    @Autowired
    public EbayDelistService(TaskMapper taskMapper,
                             TaskItemMapper taskItemMapper,
                             EbaySellApiClient ebayClient,
                             EbayProperties properties) {
        this(taskMapper, taskItemMapper, ebayClient, properties,
                command -> Thread.ofVirtual().name("Ebay-Delist").start(command));
    }

    EbayDelistService(TaskMapper taskMapper,
                      TaskItemMapper taskItemMapper,
                      EbaySellApiClient ebayClient,
                      EbayProperties properties,
                      Executor executor) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.ebayClient = ebayClient;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 启动下架任务。
     *
     * <p>枚举在架商品需要按 SKU 逐个查询 eBay，账号商品多时要好几分钟，
     * 因此这里只建任务并立即返回，枚举和下架都在后台线程完成。
     *
     * @param styleIds 需要下架的货号；为空表示下架全部在架商品
     * @return 任务ID；已有下架任务在运行时返回 null
     */
    public synchronized Long start(List<String> styleIds) {
        return start(styleIds, Operation.DELIST);
    }

    public synchronized Long startZeroStock(List<String> styleIds) {
        return start(styleIds, Operation.ZERO_STOCK);
    }

    private Long start(List<String> styleIds, Operation operation) {
        List<String> targets = normalizeStyleIds(styleIds);
        if (hasRunningInventoryMutation()) {
            return null;
        }

        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(operation.taskType);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("styleIds", targets)
                .fluentPut("scope", targets.isEmpty() ? "all" : "style_ids")
                .fluentPut("operation", operation.code)
                .fluentPut("marketplaceId", properties.getDefaultMarketplaceId())
                .toJSONString());
        taskMapper.insert(task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(task.getId(), cancelled);
        try {
            executor.execute(() -> discoverAndRun(task.getId(), targets, cancelled, operation));
            return task.getId();
        } catch (RuntimeException e) {
            running.remove(task.getId());
            taskMapper.updateTaskFailed(task.getId(), operation.label + "任务启动失败");
            throw e;
        }
    }

    /**
     * 后台枚举在架商品并执行下架。枚举结果为空或枚举本身失败时，
     * 任务直接标记失败，失败原因写回任务记录。
     */
    void discoverAndRun(Long taskId, List<String> targets, AtomicBoolean cancelled) {
        discoverAndRun(taskId, targets, cancelled, Operation.DELIST);
    }

    private void discoverAndRun(Long taskId, List<String> targets, AtomicBoolean cancelled,
                                Operation operation) {
        List<DelistTarget> listings;
        try {
            if (cancelled.get()) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                running.remove(taskId);
                return;
            }
            listings = resolveActiveListings(targets, cancelled);
        } catch (Exception e) {
            log.error("eBay{}任务枚举在架商品失败, taskId:{}", operation.label, taskId, e);
            taskMapper.updateTaskFailed(taskId, "枚举在架商品失败：" + safeError(e));
            running.remove(taskId);
            return;
        }
        if (cancelled.get()) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            running.remove(taskId);
            return;
        }
        if (listings.isEmpty()) {
            taskMapper.updateTaskFailed(taskId, targets.isEmpty()
                    ? "没有找到在架的eBay商品"
                    : "指定货号没有找到在架的eBay商品：" + String.join("、", targets));
            running.remove(taskId);
            return;
        }
        JSONObject initialAttributes = new JSONObject(true)
                .fluentPut("total", operation == Operation.ZERO_STOCK
                        ? listings.stream().map(DelistTarget::sku).distinct().count()
                        : listings.size())
                .fluentPut("failed", 0);
        if (operation == Operation.ZERO_STOCK) {
            initialAttributes.put("zeroed", 0);
            initialAttributes.put("alreadyZero", 0);
        } else {
            initialAttributes.put("delisted", 0);
            initialAttributes.put("alreadyEnded", 0);
        }
        taskMapper.updateTaskAttributes(taskId, initialAttributes.toJSONString());
        if (operation == Operation.ZERO_STOCK) {
            runZeroStock(taskId, listings, cancelled);
        } else {
            run(taskId, listings, cancelled);
        }
    }

    public void cancel(Long taskId) {
        AtomicBoolean cancelled = running.get(taskId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    public boolean canRun() {
        return !hasRunningInventoryMutation();
    }

    private boolean hasRunningInventoryMutation() {
        return !running.isEmpty()
                || taskMapper.selectRunningTask("ebay", TASK_TYPE,
                TaskDO.TaskStatusEnum.RUNNING.getCode()) != null
                || taskMapper.selectRunningTask("ebay", ZERO_STOCK_TASK_TYPE,
                TaskDO.TaskStatusEnum.RUNNING.getCode()) != null;
    }

    private void runZeroStock(Long taskId, List<DelistTarget> listings, AtomicBoolean cancelled) {
        int changed = 0;
        int alreadyZero = 0;
        int failed = 0;
        Set<String> processedSkus = new java.util.HashSet<>();
        try {
            for (DelistTarget listing : listings) {
                if (cancelled.get()) {
                    break;
                }
                if (!processedSkus.add(listing.sku())) {
                    continue;
                }
                String result;
                Integer previousQuantity = null;
                try {
                    previousQuantity = ebayClient.updateInventoryItemQuantity(
                            listing.sku(), 0, "en-US");
                    if (previousQuantity == 0) {
                        alreadyZero++;
                        result = "库存已为0";
                    } else {
                        changed++;
                        result = "库存清零成功(原库存" + previousQuantity + ")";
                    }
                } catch (Exception e) {
                    failed++;
                    result = "库存清零失败(" + safeError(e) + ")";
                    log.warn("eBay库存清零失败, taskId:{}, offerId:{}, sku:{}",
                            taskId, listing.offerId(), listing.sku(), e);
                }
                recordItem(taskId, listing, result, previousQuantity);
            }
            taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                    .fluentPut("total", processedSkus.size())
                    .fluentPut("zeroed", changed)
                    .fluentPut("alreadyZero", alreadyZero)
                    .fluentPut("failed", failed)
                    .toJSONString());
            if (cancelled.get()) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            } else if (failed == 0) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            } else {
                taskMapper.updateTaskFailed(taskId, "库存清零完成：成功 " + (changed + alreadyZero)
                        + "，失败 " + failed + "，请查看任务明细");
            }
            log.info("eBay库存清零任务完成, taskId:{}, total:{}, zeroed:{}, alreadyZero:{}, failed:{}",
                    taskId, processedSkus.size(), changed, alreadyZero, failed);
        } catch (Exception e) {
            log.error("eBay库存清零任务异常, taskId:{}", taskId, e);
            taskMapper.updateTaskFailed(taskId, "库存清零任务异常：" + safeError(e));
        } finally {
            running.remove(taskId);
        }
    }

    void run(Long taskId, List<DelistTarget> listings, AtomicBoolean cancelled) {
        int delisted = 0;
        int alreadyEnded = 0;
        int failed = 0;
        try {
            for (DelistTarget listing : listings) {
                if (cancelled.get()) {
                    break;
                }
                String result;
                try {
                    boolean ended = ebayClient.withdrawOffer(listing.offerId());
                    if (ended) {
                        delisted++;
                        result = "下架成功";
                    } else {
                        alreadyEnded++;
                        result = "已下架";
                    }
                } catch (Exception e) {
                    failed++;
                    result = "下架失败(" + safeError(e) + ")";
                    log.warn("eBay下架失败, taskId:{}, offerId:{}, sku:{}",
                            taskId, listing.offerId(), listing.sku(), e);
                }
                recordItem(taskId, listing, result);
            }
            taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                    .fluentPut("total", listings.size())
                    .fluentPut("delisted", delisted)
                    .fluentPut("alreadyEnded", alreadyEnded)
                    .fluentPut("failed", failed)
                    .toJSONString());
            if (cancelled.get()) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            } else if (failed == 0) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            } else {
                taskMapper.updateTaskFailed(taskId, "下架完成：成功 " + (delisted + alreadyEnded)
                        + "，失败 " + failed + "，请查看任务明细");
            }
            log.info("eBay下架任务完成, taskId:{}, total:{}, delisted:{}, alreadyEnded:{}, failed:{}",
                    taskId, listings.size(), delisted, alreadyEnded, failed);
        } catch (Exception e) {
            log.error("eBay下架任务异常, taskId:{}", taskId, e);
            taskMapper.updateTaskFailed(taskId, "下架任务异常：" + safeError(e));
        } finally {
            running.remove(taskId);
        }
    }

    /**
     * 以 eBay 账号上的在架 offer 为准列出下架目标，并用本地映射补齐展示信息。
     *
     * <p>按货号过滤时，只保留本地映射能确认货号的 offer；没有映射的 offer
     * 无法判断货号，因此仅在全量下架时纳入。
     */
    List<DelistTarget> resolveActiveListings(List<String> styleIds) {
        return resolveActiveListings(styleIds, new AtomicBoolean(false));
    }

    private List<DelistTarget> resolveActiveListings(List<String> styleIds, AtomicBoolean cancelled) {
        Map<String, TaskItemDO> mappingsBySku = mappingsBySku();
        Set<String> wanted = Set.copyOf(styleIds);
        Map<String, DelistTarget> byOfferId = new LinkedHashMap<>();
        for (String sku : ebayClient.getInventoryItemSkus()) {
            if (cancelled.get()) {
                break;
            }
            for (JSONObject offer : ebayClient.getOffersBySku(sku)) {
                if (cancelled.get()) {
                    break;
                }
                if (offer == null || !ebayClient.isActiveOffer(offer)) {
                    continue;
                }
                String offerId = offer.getString("offerId");
                if (offerId == null || offerId.isBlank()) {
                    continue;
                }
                TaskItemDO mapping = mappingsBySku.get(sku);
                String styleId = mapping == null ? null : mapping.getStyleId();
                if (!wanted.isEmpty() && (styleId == null
                        || !wanted.contains(styleId.trim().toUpperCase(Locale.ROOT)))) {
                    continue;
                }
                byOfferId.putIfAbsent(offerId, new DelistTarget(offerId, sku,
                        listingId(offer), mapping));
            }
        }
        return List.copyOf(byOfferId.values());
    }

    private Map<String, TaskItemDO> mappingsBySku() {
        List<TaskItemDO> mappings = taskItemMapper.selectEbayListingMappings();
        Map<String, TaskItemDO> bySku = new LinkedHashMap<>();
        for (TaskItemDO mapping : mappings == null ? List.<TaskItemDO>of() : mappings) {
            if (mapping.getSku() != null && !mapping.getSku().isBlank()) {
                bySku.putIfAbsent(mapping.getSku(), mapping);
            }
        }
        return bySku;
    }

    private String listingId(JSONObject offer) {
        JSONObject listing = offer.getJSONObject("listing");
        return listing == null ? null : listing.getString("listingId");
    }

    /**
     * 一个待下架的在架 offer。mapping 可能为空，表示本地没有留下映射记录。
     */
    record DelistTarget(String offerId, String sku, String listingId, TaskItemDO mapping) {
    }

    private List<String> normalizeStyleIds(List<String> styleIds) {
        if (styleIds == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String styleId : styleIds) {
            if (styleId == null || styleId.isBlank()) {
                continue;
            }
            String value = styleId.trim().toUpperCase(Locale.ROOT);
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private void recordItem(Long taskId, DelistTarget listing, String result) {
        recordItem(taskId, listing, result, null);
    }

    private void recordItem(Long taskId, DelistTarget listing, String result,
                            Integer previousQuantity) {
        TaskItemDO mapping = listing.mapping();
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setSku(listing.sku());
        item.setOfferId(listing.offerId());
        item.setListingId(listing.listingId());
        if (mapping != null) {
            item.setBrand(mapping.getBrand());
            item.setTitle(mapping.getTitle());
            item.setStyleId(mapping.getStyleId());
            item.setSize(mapping.getSize());
            item.setEuSize(mapping.getEuSize());
            item.setCurrentPrice(mapping.getCurrentPrice());
            if (item.getListingId() == null || item.getListingId().isBlank()) {
                item.setListingId(mapping.getListingId());
            }
        }
        item.setListingQuantity(previousQuantity == null ? 0 : previousQuantity);
        item.setOperateResult(result);
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 120 ? message : message.substring(0, 120);
    }

    private enum Operation {
        DELIST(TASK_TYPE, "delist", "下架"),
        ZERO_STOCK(ZERO_STOCK_TASK_TYPE, "zero_stock", "库存清零");

        private final String taskType;
        private final String code;
        private final String label;

        Operation(String taskType, String code, String label) {
            this.taskType = taskType;
            this.code = code;
            this.label = label;
        }
    }
}
