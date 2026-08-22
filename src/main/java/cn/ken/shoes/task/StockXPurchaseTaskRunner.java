package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
public class StockXPurchaseTaskRunner implements Runnable {

    private static final int MAX_PAGE_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MS = 5 * 60 * 1000L;

    private final StockXAccount account;
    private final Long taskId;
    private final StockXPurchaseOperation operation;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXPurchaseTaskRunner(StockXAccount account, Long taskId,
                                    StockXPurchaseOperation operation, StockXClient stockXClient,
                                    TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.operation = operation;
        this.stockXClient = stockXClient;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    @Override
    public void run() {
        TaskSwitch.setPurchaseRunning(account.getName(), true);
        long startTime = System.currentTimeMillis();
        int pages = 0;
        int total = 0;
        String after = null;
        try {
            while (true) {
                ensureNotCancelled();
                String currentCursor = after;
                int currentPage = pages + 1;
                JSONObject result = queryPageWithRetry(currentPage,
                        () -> stockXClient.queryPurchasePage(operation, currentCursor, account));
                JSONArray edges = result.getJSONArray("edges");
                List<TaskItemDO> items = new ArrayList<>();
                for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                    JSONObject node = edge.getJSONObject("node");
                    if (node != null) {
                        items.add(StockXPurchaseItemConverter.convert(taskId, node, operation));
                    }
                }
                for (TaskItemDO item : items) {
                    ensureNotCancelled();
                    taskItemMapper.insert(item);
                }
                total += items.size();
                pages++;
                taskMapper.updateTaskRound(taskId, pages);
                taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                        .fluentPut("operation", operation.getCode())
                        .fluentPut("total", total)
                        .toJSONString());

                JSONObject pageInfo = result.getJSONObject("pageInfo");
                boolean hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
                if (!hasNextPage) {
                    break;
                }
                String nextCursor = pageInfo.getString("endCursor");
                if (StrUtil.isBlank(nextCursor) || nextCursor.equals(after)) {
                    throw new IllegalStateException(operation.getLabel() + "分页游标无效");
                }
                after = nextCursor;
            }

            String cost = TimeUtil.getCostMin(startTime);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, cost);
            taskMapper.updateTaskFailReason(taskId, "共获取" + total + "条记录");
            log.info("[{}] 购买任务完成, operation:{}, total:{}, pages:{}, cost:{}",
                    account.getName(), operation.getCode(), total, pages, cost);
        } catch (TaskCancelledException ignored) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
            log.info("[{}] 购买任务已取消, operation:{}", account.getName(), operation.getCode());
        } catch (Exception e) {
            log.error("[{}] 购买任务异常, operation:{}", account.getName(), operation.getCode(), e);
            String reason = StrUtil.blankToDefault(e.getMessage(), "未知异常");
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
            taskMapper.updateTaskFailed(taskId, reason.substring(0, Math.min(reason.length(), 200)));
        } finally {
            TaskSwitch.clearPurchaseState(account.getName());
        }
    }

    private JSONObject queryPageWithRetry(int pageNumber, Supplier<JSONObject> query) {
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
                if (result != null && result.getJSONArray("edges") != null
                        && result.getJSONObject("pageInfo") != null) {
                    if (attempt > 1) {
                        taskMapper.updateTaskFailReason(taskId, null);
                    }
                    return result;
                }
                lastFailure = result == null
                        ? "StockX无响应（网络、代理或风控拦截）"
                        : "StockX响应缺少购买数据";
            } catch (StockXRateLimitException e) {
                lastException = e;
                lastFailure = StrUtil.blankToDefault(e.getMessage(), "StockX请求限流");
                delayMs = Math.max(delayMs, Math.min(e.getCooldownMs(), MAX_RETRY_DELAY_MS));
            }

            if (attempt < MAX_PAGE_ATTEMPTS) {
                taskMapper.updateTaskFailReason(taskId, operation.getLabel() + "第" + pageNumber
                        + "页请求异常，" + (delayMs / 1000) + "秒后重试（" + attempt + "/"
                        + (MAX_PAGE_ATTEMPTS - 1) + "）");
                waitBeforeRetry(delayMs);
            }
        }
        throw new IllegalStateException(operation.getLabel() + "第" + pageNumber
                + "页查询失败（已重试" + (MAX_PAGE_ATTEMPTS - 1) + "次：" + lastFailure + "）",
                lastException);
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

    private long retryDelayMs(int attempt) {
        return attempt == 1 ? 2000L : 5000L;
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isPurchaseCancelled(account.getName())) {
            throw new TaskCancelledException();
        }
    }

    private static class TaskCancelledException extends RuntimeException {
    }
}
