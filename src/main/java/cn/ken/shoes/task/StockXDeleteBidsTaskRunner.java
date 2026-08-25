package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidDeleteItem;
import cn.ken.shoes.model.stockx.StockXBidDeleteResult;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 后台撤销账号全部当前有效出价。 */
@Slf4j
public class StockXDeleteBidsTaskRunner implements Runnable {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_NO_PROGRESS_ROUNDS = 3;
    private static final int ZERO_CONFIRMATIONS = 3;
    private static final int MAX_QUERY_ATTEMPTS = 3;
    private static final long NEXT_CHECK_DELAY_MS = 2_000L;

    private final StockXAccount account;
    private final Long taskId;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXDeleteBidsTaskRunner(StockXAccount account, Long taskId,
                                      StockXClient stockXClient, TaskMapper taskMapper,
                                      TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.stockXClient = stockXClient;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    @Override
    public void run() {
        String accountName = account.getName();
        TaskSwitch.setPurchaseRunning(accountName, true);
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isPurchaseCancelled(accountName),
                reason -> taskMapper.updateTaskFailReason(taskId, reason),
                () -> taskMapper.updateTaskFailReason(taskId, null));
        long start = System.currentTimeMillis();
        int round = 0;
        int deleted = 0;
        int failed = 0;
        int zeroConfirmations = 0;
        int noProgressRounds = 0;
        int total = 0;
        Set<String> observed = new HashSet<>();
        try {
            while (true) {
                ensureNotCancelled();
                JSONObject page = loadFirstPageWithRetry();
                List<JSONObject> bids = extractBids(page);
                int remaining = page.containsKey("totalCount")
                        ? Math.max(0, page.getIntValue("totalCount"))
                        : bids.size();
                total = Math.max(total, deleted + remaining);

                if (bids.isEmpty()) {
                    zeroConfirmations++;
                    String stage = zeroConfirmations >= ZERO_CONFIRMATIONS
                            ? "已完成"
                            : "确认清零 " + zeroConfirmations + "/" + ZERO_CONFIRMATIONS;
                    updateProgress(stage, total, 0, observed.size(), deleted, failed, round);
                    if (zeroConfirmations >= ZERO_CONFIRMATIONS) {
                        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
                        taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
                        taskMapper.updateTaskFailReason(taskId, null);
                        log.info("[{}] 撤销所有出价完成, taskId:{}, deleted:{}, failedAttempts:{}, rounds:{}",
                                accountName, taskId, deleted, failed, round);
                        return;
                    }
                    waitBeforeNextCheck(NEXT_CHECK_DELAY_MS);
                    continue;
                }

                zeroConfirmations = 0;
                round++;
                taskMapper.updateTaskRound(taskId, round);
                bids.forEach(node -> {
                    String id = StrUtil.trim(node.getString("id"));
                    if (StrUtil.isNotBlank(id)) observed.add(id);
                });
                int deletedThisRound = 0;

                for (int offset = 0; offset < bids.size(); offset += BATCH_SIZE) {
                    ensureNotCancelled();
                    List<JSONObject> nodes = bids.subList(offset, Math.min(offset + BATCH_SIZE, bids.size()));
                    List<StockXBidDeleteItem> requests = nodes.stream()
                            .map(node -> new StockXBidDeleteItem(node.getString("id"),
                                    StrUtil.blankToDefault(node.getString("currencyCode"), "USD")))
                            .toList();
                    try {
                        List<StockXBidDeleteResult> results = stockXClient.deleteBids(requests, account);
                        for (int i = 0; i < results.size(); i++) {
                            StockXBidDeleteResult result = results.get(i);
                            TaskItemDO item = taskItem(nodes.get(i), round);
                            if (result.success()) {
                                item.setOrderStatus("已撤销");
                                item.setOperateResult("撤销成功");
                                deleted++;
                                deletedThisRound++;
                            } else {
                                item.setOrderStatus("撤销失败");
                                item.setOperateResult("撤销失败-" + safeReason(result.status()));
                                failed++;
                            }
                            taskItemMapper.insert(item);
                        }
                    } catch (StockXRateLimitException | TaskCancelledException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                            throw e;
                        }
                        String reason = safeReason(StrUtil.blankToDefault(e.getMessage(), "StockX拒绝撤销出价"));
                        for (JSONObject node : nodes) {
                            TaskItemDO item = taskItem(node, round);
                            item.setOrderStatus("撤销失败");
                            item.setOperateResult("撤销失败-" + reason);
                            taskItemMapper.insert(item);
                            failed++;
                        }
                    }
                    updateProgress("撤销中", total, Math.max(0, remaining - deletedThisRound),
                            observed.size(), deleted, failed, round);
                    taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
                }

                if (deletedThisRound == 0) {
                    noProgressRounds++;
                    if (noProgressRounds >= MAX_NO_PROGRESS_ROUNDS) {
                        throw new IllegalStateException("连续3轮没有成功撤销出价，任务已停止，请检查任务明细");
                    }
                } else {
                    noProgressRounds = 0;
                }
                waitBeforeNextCheck(NEXT_CHECK_DELAY_MS);
            }
        } catch (TaskCancelledException e) {
            updateProgress("已取消", total, Math.max(0, total - deleted),
                    observed.size(), deleted, failed, round);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (StockXRateLimitException e) {
            taskMapper.updateTaskPaused(taskId, e.getMessage());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
            String reason = "TOKEN_EXPIRED".equals(e.getMessage())
                    ? "StockX Token已过期，请更新Token"
                    : StrUtil.blankToDefault(e.getMessage(), "撤销所有出价任务异常");
            taskMapper.updateTaskFailed(taskId, safeReason(reason));
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            log.error("[{}] 撤销所有出价任务异常, taskId:{}", accountName, taskId, e);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearPurchaseState(accountName);
        }
    }

    private JSONObject loadFirstPageWithRetry() {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_QUERY_ATTEMPTS; attempt++) {
            ensureNotCancelled();
            try {
                JSONObject page = stockXClient.queryPurchasePage(
                        StockXPurchaseOperation.BIDS, null, account);
                if (page != null && page.getBooleanValue("_unauthorized")) {
                    throw new IllegalStateException("TOKEN_EXPIRED");
                }
                if (page != null && page.getJSONArray("edges") != null
                        && page.getJSONObject("pageInfo") != null) {
                    if (attempt > 1) taskMapper.updateTaskFailReason(taskId, null);
                    return page;
                }
            } catch (StockXRateLimitException e) {
                lastException = e;
            }
            if (attempt < MAX_QUERY_ATTEMPTS) {
                taskMapper.updateTaskFailReason(taskId, "读取当前有效出价失败，2秒后重试（"
                        + attempt + "/" + (MAX_QUERY_ATTEMPTS - 1) + "）");
                waitBeforeNextCheck(NEXT_CHECK_DELAY_MS);
            }
        }
        throw new IllegalStateException("读取当前有效出价失败（已重试2次），已停止撤销", lastException);
    }

    private List<JSONObject> extractBids(JSONObject page) {
        JSONArray edges = page.getJSONArray("edges");
        List<JSONObject> bids = new ArrayList<>();
        for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            if (node == null || StrUtil.isBlank(node.getString("id"))) {
                throw new IllegalStateException("当前有效出价数据缺少chainId，已停止撤销");
            }
            bids.add(node);
        }
        return bids;
    }

    private TaskItemDO taskItem(JSONObject node, int round) {
        TaskItemDO item = StockXPurchaseItemConverter.convert(taskId, node, StockXPurchaseOperation.BIDS);
        item.setRound(round);
        item.setOperateTime(new Date());
        return item;
    }

    private void updateProgress(String stage, int total, int remaining, int processed,
                                int deleted, int failed, int round) {
        taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                .fluentPut("operation", StockXPurchaseOperation.DELETE_BIDS.getCode())
                .fluentPut("stage", stage)
                .fluentPut("total", total)
                .fluentPut("remaining", remaining)
                .fluentPut("processed", processed)
                .fluentPut("deleted", deleted)
                .fluentPut("failed", failed)
                .fluentPut("round", round)
                .toJSONString());
    }

    private String safeReason(String reason) {
        String sanitized = StrUtil.blankToDefault(reason, "未知原因")
                .replace('\n', ' ').replace('\r', ' ')
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer ***");
        return sanitized.substring(0, Math.min(sanitized.length(), 200));
    }

    protected void waitBeforeNextCheck(long delayMs) {
        long remaining = delayMs;
        while (remaining > 0) {
            ensureNotCancelled();
            long sleepMs = Math.min(remaining, 1_000L);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException();
            }
            remaining -= sleepMs;
        }
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isPurchaseCancelled(account.getName()) || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }
}
