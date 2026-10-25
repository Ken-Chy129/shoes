package cn.ken.shoes.service;

import cn.hutool.json.JSONUtil;
import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskOperationCount;
import cn.ken.shoes.model.task.TaskRequest;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskService {

    private static final long OPERATION_COUNT_CACHE_SECONDS = 15;

    private final TaskMapper taskMapper;

    private final TaskItemMapper taskItemMapper;

    private final TaskExecutorManager taskExecutorManager;

    private final Cache<List<Long>, List<TaskOperationCount>> operationCountCache = CacheBuilder.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(OPERATION_COUNT_CACHE_SECONDS, TimeUnit.SECONDS)
            .build();

    /** 对账最长 30 分钟判超时，结束 40 分钟后统计视为稳定。 */
    static final long SETTLE_AFTER_END_MS = 40 * 60 * 1000L;

    private final Cache<SettledKey, TaskOperationCount> settledCountCache = CacheBuilder.newBuilder()
            .maximumSize(5000)
            .expireAfterAccess(12, TimeUnit.HOURS)
            .build();

    public TaskService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                       TaskExecutorManager taskExecutorManager) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.taskExecutorManager = taskExecutorManager;
    }

    public PageResult<List<TaskDO>> queryTasksByCondition(TaskRequest request) {
        Long count = taskMapper.count(request);
        if (count == 0) {
            return PageResult.buildSuccess();
        }
        List<TaskDO> taskDOS = taskMapper.selectByCondition(request);
        if (taskDOS == null || taskDOS.isEmpty()) {
            PageResult<List<TaskDO>> emptyResult = PageResult.buildSuccess();
            emptyResult.setTotal(count);
            return emptyResult;
        }
        Map<Long, TaskOperationCount> countMap = resolveOperationCounts(taskDOS);
        for (TaskDO taskDO : taskDOS) {
            TaskOperationCount operationCount = countMap.get(taskDO.getId());
            if (operationCount != null) {
                taskDO.setPriceDownCount(operationCount.getPriceDownCount());
                taskDO.setListingCount(operationCount.getListingCount());
                taskDO.setDelistCount(operationCount.getDelistCount());
                taskDO.setPendingOperationCount(operationCount.getPendingOperationCount());
            }
            taskDO.setRerunnable(taskExecutorManager.canRerun(taskDO));
            Optional.ofNullable(TaskDO.TaskTypeEnum.from(taskDO.getTaskType())).ifPresent(taskType -> taskDO.setTaskType(taskType.getName()));
            Optional.ofNullable(TaskDO.TaskStatusEnum.from(taskDO.getStatus())).ifPresent(status -> taskDO.setStatus(status.getName()));
        }
        PageResult<List<TaskDO>> result = PageResult.buildSuccess(taskDOS);
        result.setTotal(count);
        return result;
    }

    /**
     * 已结束且超过对账窗口的任务，统计不会再变化：按 (taskId, endTime) 长期缓存，只算一次。
     * endTime 参与 key，任务被继续执行后再次结束时 endTime 改变，自然失效旧值。
     * 运行中/刚结束的任务仍实时聚合（短 TTL 合并并发请求）。
     */
    private Map<Long, TaskOperationCount> resolveOperationCounts(List<TaskDO> tasks) {
        long now = System.currentTimeMillis();
        Map<Long, TaskOperationCount> result = new HashMap<>();
        Map<Long, SettledKey> settledKeys = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (TaskDO task : tasks) {
            SettledKey key = settledKey(task, now);
            TaskOperationCount cached = key == null ? null : settledCountCache.getIfPresent(key);
            if (cached != null) {
                result.put(task.getId(), cached);
                continue;
            }
            if (key != null) {
                settledKeys.put(task.getId(), key);
            }
            missing.add(task.getId());
        }
        if (missing.isEmpty()) {
            return result;
        }
        Map<Long, TaskOperationCount> fetched = queryOperationCounts(missing).stream()
                .collect(Collectors.toMap(TaskOperationCount::getTaskId, Function.identity(), (a, b) -> a));
        for (Long taskId : missing) {
            TaskOperationCount count = fetched.getOrDefault(taskId, new TaskOperationCount(taskId, 0L, 0L, 0L, 0L));
            result.put(taskId, count);
            SettledKey key = settledKeys.get(taskId);
            if (key != null && (count.getPendingOperationCount() == null || count.getPendingOperationCount() == 0)) {
                settledCountCache.put(key, count);
            }
        }
        return result;
    }

    static SettledKey settledKey(TaskDO task, long now) {
        if (task.getId() == null || task.getEndTime() == null
                || TaskDO.TaskStatusEnum.RUNNING.getCode().equals(task.getStatus())) {
            return null;
        }
        long endTime = task.getEndTime().getTime();
        if (endTime > now - SETTLE_AFTER_END_MS) {
            return null;
        }
        return new SettledKey(task.getId(), endTime);
    }

    record SettledKey(Long taskId, long endTime) {
    }

    private List<TaskOperationCount> queryOperationCounts(List<Long> taskIds) {
        List<Long> cacheKey = taskIds.stream().sorted().toList();
        try {
            return operationCountCache.get(cacheKey, () -> {
                List<TaskOperationCount> counts = taskItemMapper.selectOperationCountsByTaskIds(cacheKey);
                return counts == null ? List.of() : List.copyOf(counts);
            });
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("查询任务操作统计失败", cause);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task != null) {
            if (TaskDO.TaskStatusEnum.RUNNING.getCode().equals(task.getStatus())) {
                throw new IllegalStateException("运行中的任务不能删除，请先终止任务");
            }
            clearTaskRunningState(task);
        }
        taskItemMapper.deleteByTaskId(taskId);
        taskMapper.deleteById(taskId);
    }

    public void cancelTaskById(Long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        dispatchTaskSwitch(task, false);
    }

    public Long resumeTaskById(Long taskId) {
        TaskDO task = requireTask(taskId);
        if (!TaskDO.TaskStatusEnum.PAUSED.getCode().equals(task.getStatus())) {
            throw new IllegalStateException("只有已暂停任务可以继续执行");
        }
        Long resumedTaskId = taskExecutorManager.resumePausedTask(task);
        if (resumedTaskId == null) {
            throw new IllegalStateException("任务无法继续执行，可能已有同账号任务运行或原始输入已丢失");
        }
        return resumedTaskId;
    }

    public Long rerunTaskById(Long taskId) {
        TaskDO task = requireTask(taskId);
        if (TaskDO.TaskStatusEnum.RUNNING.getCode().equals(task.getStatus())) {
            throw new IllegalStateException("运行中的任务不能重跑，请先终止或等待结束");
        }
        if (!taskExecutorManager.canRerun(task)) {
            throw new IllegalStateException("该历史任务类型不支持重跑");
        }
        Long newTaskId = taskExecutorManager.rerunTask(task);
        if (newTaskId == null) {
            throw new IllegalStateException("任务重跑失败，可能已有同账号任务运行或原始输入已丢失");
        }
        return newTaskId;
    }

    private TaskDO requireTask(Long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    private void clearTaskRunningState(TaskDO task) {
        dispatchTaskSwitch(task, true);
    }

    private void dispatchTaskSwitch(TaskDO task, boolean clearState) {
        String taskType = task.getTaskType();
        String accountName = task.getAccountName();
        if (taskType == null || accountName == null) {
            return;
        }
        String inventoryType = "STANDARD";
        ListingFetchMode fetchMode = ListingFetchMode.ALL;
        StockXPurchaseOperation purchaseOperation = null;
        try {
            if (task.getParams() != null) {
                var params = JSONUtil.parseObj(task.getParams());
                inventoryType = params.getStr("inventoryType", "STANDARD");
                fetchMode = ListingFetchMode.fromCode(params.getStr("listingFetchMode"));
                purchaseOperation = StockXPurchaseOperation.fromCode(params.getStr("operation"));
            }
        } catch (Exception ignored) {}

        String key = accountName + ":" + inventoryType;
        switch (taskType) {
            case "price_down" -> {
                if (clearState) {
                    TaskSwitch.clearExcelState(accountName, inventoryType, fetchMode);
                } else {
                    TaskSwitch.cancelExcel(accountName, inventoryType, fetchMode);
                }
            }
            case "listing", "model_search", "purchase_guidance" -> {
                if (clearState) {
                    TaskSwitch.clearSearchListRunState(task.getId());
                } else {
                    TaskSwitch.cancelSearchList(task.getId());
                    TaskSwitch.cancelSearchVerification(task.getId());
                }
            }
            case "fetch_listings" -> { if (clearState) TaskSwitch.clearFetchListingsState(key); else TaskSwitch.cancelFetchListings(key); }
            case "excel_delist" -> { if (clearState) TaskSwitch.clearExcelDelistState(key); else TaskSwitch.cancelExcelDelist(key); }
            case "fetch_orders" -> { if (clearState) TaskSwitch.clearFetchOrdersState(accountName); else TaskSwitch.cancelFetchOrders(accountName); }
            case "purchase" -> {
                if (purchaseOperation == null) {
                    if (clearState) TaskSwitch.clearAllPurchaseStates(accountName); else TaskSwitch.cancelAllPurchases(accountName);
                } else if (clearState) {
                    TaskSwitch.clearPurchaseState(accountName, purchaseOperation);
                } else {
                    TaskSwitch.cancelPurchase(accountName, purchaseOperation);
                }
            }
            case "ebay_price_sync" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayPriceSync(task.getId());
                }
            }
            case "ebay_bulk_listing" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayBulkListing(task.getId());
                }
            }
            case "ebay_poison_listing" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayPoisonListing(task.getId());
                }
            }
            case "ebay_profit_price" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayProfitPrice(task.getId());
                }
            }
            case "ebay_add_tracking" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayAddTracking(task.getId());
                }
            }
            case "ebay_fetch_orders" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayFetchOrders(task.getId());
                }
            }
            case "ebay_delist", "ebay_zero_stock" -> {
                if (!clearState) {
                    taskExecutorManager.cancelEbayDelist(task.getId());
                }
            }
            default -> log.info("任务类型无需处理TaskSwitch: taskType={}", taskType);
        }
    }
}
