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
 * <p>下架范围来自批量上架任务留下的 task_item 映射，可选按货号过滤；
 * 不传货号表示下架当前记录在册的全部 eBay 在架商品。
 */
@Slf4j
@Service
public class EbayDelistService {

    public static final String TASK_TYPE = "ebay_delist";

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
     * @param styleIds 需要下架的货号；为空表示下架全部在架商品
     * @return 任务ID；已有下架任务在运行时返回 null
     */
    public synchronized Long start(List<String> styleIds) {
        List<String> targets = normalizeStyleIds(styleIds);
        TaskDO existing = taskMapper.selectRunningTask(
                "ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode());
        if (existing != null || !running.isEmpty()) {
            return null;
        }
        List<TaskItemDO> listings = resolveListings(targets);
        if (listings.isEmpty()) {
            throw new IllegalArgumentException(targets.isEmpty()
                    ? "没有找到在架的eBay商品"
                    : "指定货号没有找到在架的eBay商品：" + String.join("、", targets));
        }

        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("styleIds", targets)
                .fluentPut("scope", targets.isEmpty() ? "all" : "style_ids")
                .fluentPut("plannedCount", listings.size())
                .fluentPut("marketplaceId", properties.getDefaultMarketplaceId())
                .toJSONString());
        taskMapper.insert(task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(task.getId(), cancelled);
        try {
            executor.execute(() -> run(task.getId(), listings, cancelled));
            return task.getId();
        } catch (RuntimeException e) {
            running.remove(task.getId());
            taskMapper.updateTaskFailed(task.getId(), "下架任务启动失败");
            throw e;
        }
    }

    public void cancel(Long taskId) {
        AtomicBoolean cancelled = running.get(taskId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    public boolean canRun() {
        return running.isEmpty()
                && taskMapper.selectRunningTask(
                "ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode()) == null;
    }

    void run(Long taskId, List<TaskItemDO> listings, AtomicBoolean cancelled) {
        int delisted = 0;
        int alreadyEnded = 0;
        int failed = 0;
        try {
            for (TaskItemDO listing : listings) {
                if (cancelled.get()) {
                    break;
                }
                String result;
                try {
                    boolean ended = ebayClient.withdrawOffer(listing.getOfferId());
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
                            taskId, listing.getOfferId(), listing.getSku(), e);
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
     * 汇总当前记录在册的在架商品，按 offerId 去重并保留最新一条映射。
     */
    List<TaskItemDO> resolveListings(List<String> styleIds) {
        List<TaskItemDO> mappings = taskItemMapper.selectEbayListingMappings();
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = Set.copyOf(styleIds);
        Map<String, TaskItemDO> byOfferId = new LinkedHashMap<>();
        for (TaskItemDO mapping : mappings) {
            String offerId = mapping.getOfferId();
            if (offerId == null || offerId.isBlank()) {
                continue;
            }
            if (!wanted.isEmpty() && (mapping.getStyleId() == null
                    || !wanted.contains(mapping.getStyleId().trim().toUpperCase(Locale.ROOT)))) {
                continue;
            }
            byOfferId.putIfAbsent(offerId, mapping);
        }
        return List.copyOf(byOfferId.values());
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

    private void recordItem(Long taskId, TaskItemDO listing, String result) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setBrand(listing.getBrand());
        item.setTitle(listing.getTitle());
        item.setListingId(listing.getListingId());
        item.setSku(listing.getSku());
        item.setOfferId(listing.getOfferId());
        item.setStyleId(listing.getStyleId());
        item.setSize(listing.getSize());
        item.setEuSize(listing.getEuSize());
        item.setCurrentPrice(listing.getCurrentPrice());
        item.setListingQuantity(0);
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
}
