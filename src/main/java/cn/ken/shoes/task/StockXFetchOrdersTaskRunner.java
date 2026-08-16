package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
public class StockXFetchOrdersTaskRunner implements Runnable {

    private static final int MAX_PAGE_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MS = 5 * 60 * 1000L;

    private final StockXAccount account;
    private final Long taskId;
    private final List<StockXOrderCategory> categories;
    private final StockXClient stockXClient;
    private final PriceManager priceManager;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXFetchOrdersTaskRunner(StockXAccount account, Long taskId,
                                       List<StockXOrderCategory> categories,
                                       StockXClient stockXClient, PriceManager priceManager,
                                       TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.categories = categories;
        this.stockXClient = stockXClient;
        this.priceManager = priceManager;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    @Override
    public void run() {
        TaskSwitch.setFetchOrdersRunning(account.getName(), true);
        long startTime = System.currentTimeMillis();
        int totalPages = 0;
        int totalOrders = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            for (StockXOrderCategory category : categories) {
                CategoryResult result = category == StockXOrderCategory.PENDING
                        ? fetchPendingOrders(totalPages)
                        : fetchHistoricalOrders(category, totalPages);
                totalPages += result.pages();
                totalOrders += result.count();
                counts.put(category.getCode(), result.count());
                taskMapper.updateTaskAttributes(taskId, new JSONObject()
                        .fluentPut("counts", counts)
                        .fluentPut("total", totalOrders)
                        .toJSONString());
            }

            String cost = TimeUtil.getCostMin(startTime);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, cost);
            taskMapper.updateTaskFailReason(taskId, "共获取" + totalOrders + "条订单");
            log.info("[{}] 获取订单任务完成, categories:{}, total:{}, 耗时:{}",
                    account.getName(), categories, totalOrders, cost);
        } catch (TaskCancelledException ignored) {
            cancelTask(startTime);
        } catch (Exception e) {
            log.error("[{}] 获取订单任务异常: {}", account.getName(), e.getMessage(), e);
            String reason = e.getMessage();
            if (reason != null && reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
            taskMapper.updateTaskFailed(taskId, reason != null ? reason : "未知异常");
        } finally {
            TaskSwitch.clearFetchOrdersState(account.getName());
        }
    }

    private CategoryResult fetchHistoricalOrders(StockXOrderCategory category, int completedPages) {
        int count = 0;
        int pages = 0;
        int pageNumber = 1;
        boolean hasNextPage;
        do {
            ensureNotCancelled();
            int currentPage = pageNumber;
            JSONObject result = queryPageWithRetry(category.getLabel(), currentPage,
                    () -> stockXClient.queryOrderListings(category, currentPage, account));
            JSONArray edges = result.getJSONArray("edges");
            List<TaskItemDO> items = new ArrayList<>();
            for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                JSONObject node = edge.getJSONObject("node");
                if (node != null) {
                    TaskItemDO item = StockXOrderItemConverter.convert(taskId, node, category);
                    if (category == StockXOrderCategory.COMPLETED && StrUtil.isNotBlank(item.getListingId())) {
                        ensureNotCancelled();
                        item.setPayoutAmount(stockXClient.queryOrderPayout(item.getListingId(), account));
                    }
                    items.add(item);
                }
            }
            storeWithoutPoisonPrices(items);
            count += items.size();
            pages++;
            taskMapper.updateTaskRound(taskId, completedPages + pages);
            JSONObject pageInfo = result.getJSONObject("pageInfo");
            hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
            pageNumber++;
        } while (hasNextPage);
        return new CategoryResult(count, pages);
    }

    private CategoryResult fetchPendingOrders(int completedPages) {
        int count = 0;
        int pages = 0;
        String after = null;
        while (true) {
            ensureNotCancelled();
            String currentCursor = after;
            int currentPage = pages + 1;
            JSONObject result = queryPageWithRetry(StockXOrderCategory.PENDING.getLabel(), currentPage,
                    () -> stockXClient.queryPendingAsks(currentCursor, account));
            JSONArray edges = result.getJSONArray("edges");
            List<TaskItemDO> items = new ArrayList<>();
            for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                JSONObject node = edge.getJSONObject("node");
                if (node != null) {
                    items.add(StockXOrderItemConverter.convertPending(taskId, node));
                }
            }
            storeWithPoisonPrices(items);
            count += items.size();
            pages++;
            taskMapper.updateTaskRound(taskId, completedPages + pages);

            JSONObject pageInfo = result.getJSONObject("pageInfo");
            boolean hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
            if (!hasNextPage) {
                return new CategoryResult(count, pages);
            }
            String nextCursor = pageInfo.getString("endCursor");
            if (StrUtil.isBlank(nextCursor) || nextCursor.equals(after)) {
                throw new IllegalStateException("待处理订单分页游标无效");
            }
            after = nextCursor;
        }
    }

    private JSONObject queryPageWithRetry(String categoryLabel, int pageNumber,
                                          Supplier<JSONObject> query) {
        String lastFailure = "StockX无响应（网络、代理或风控拦截）";
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_PAGE_ATTEMPTS; attempt++) {
            ensureNotCancelled();
            long delayMs = retryDelayMs(attempt);
            try {
                JSONObject result = query.get();
                if (result != null && result.getBooleanValue("_unauthorized")) {
                    throw new IllegalStateException("StockX Token已过期，请更新Token");
                }
                if (result != null && result.getJSONArray("edges") != null) {
                    if (attempt > 1) {
                        taskMapper.updateTaskFailReason(taskId, null);
                    }
                    return result;
                }
                if (result == null) {
                    lastFailure = "StockX无响应（网络、代理或风控拦截）";
                } else {
                    lastFailure = "StockX响应缺少订单数据";
                }
            } catch (StockXRateLimitException e) {
                lastException = e;
                lastFailure = e.getMessage() != null ? e.getMessage() : "StockX请求限流";
                delayMs = Math.max(delayMs, Math.min(e.getCooldownMs(), MAX_RETRY_DELAY_MS));
            }

            if (attempt < MAX_PAGE_ATTEMPTS) {
                String retryMessage = categoryLabel + "订单第" + pageNumber + "页请求异常，"
                        + (delayMs / 1000) + "秒后重试（" + attempt + "/" + (MAX_PAGE_ATTEMPTS - 1) + "）";
                taskMapper.updateTaskFailReason(taskId, retryMessage);
                log.warn("[{}] {}, 原因:{}", account.getName(), retryMessage, lastFailure);
                waitBeforeRetry(delayMs);
            }
        }
        throw new IllegalStateException(categoryLabel + "订单第" + pageNumber
                + "页查询失败（已重试" + (MAX_PAGE_ATTEMPTS - 1) + "次：" + lastFailure + "）",
                lastException);
    }

    private long retryDelayMs(int attempt) {
        return attempt == 1 ? 2000L : 5000L;
    }

    protected void waitBeforeRetry(long delayMs) {
        long remainingMs = delayMs;
        while (remainingMs > 0) {
            ensureNotCancelled();
            long sleepMs = Math.min(remainingMs, 1000L);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException();
            }
            remainingMs -= sleepMs;
        }
    }

    private void storeWithPoisonPrices(List<TaskItemDO> items) {
        Set<String> styleIds = new HashSet<>();
        for (TaskItemDO item : items) {
            if (StrUtil.isNotBlank(item.getStyleId())) {
                styleIds.add(item.getStyleId());
            }
        }
        priceManager.batchLoadPrices(styleIds);
        for (TaskItemDO item : items) {
            ensureNotCancelled();
            Integer poisonPrice = priceManager.getPoisonPrice(item.getStyleId(), item.getEuSize());
            if (poisonPrice != null) {
                item.setPoisonPrice(BigDecimal.valueOf(poisonPrice));
            }
            taskItemMapper.insert(item);
        }
    }

    private void storeWithoutPoisonPrices(List<TaskItemDO> items) {
        for (TaskItemDO item : items) {
            ensureNotCancelled();
            taskItemMapper.insert(item);
        }
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isFetchOrdersCancelled(account.getName())) {
            throw new TaskCancelledException();
        }
    }

    private void cancelTask(long startTime) {
        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
        log.info("[{}] 获取订单任务已取消", account.getName());
    }

    private record CategoryResult(int count, int pages) {
    }

    private static class TaskCancelledException extends RuntimeException {
    }
}
