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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 后台撤销账号全部或指定货号的当前有效出价。
 * <p>指定货号模式按货号调用 StockX 出价搜索（每个货号通常一页），不再全量分页扫描整个账号的出价；
 * 首轮检索全部目标货号，后续确认轮只复查曾匹配到出价的货号。
 */
@Slf4j
public class StockXDeleteBidsTaskRunner implements Runnable {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_NO_PROGRESS_ROUNDS = 3;
    private static final int ZERO_CONFIRMATIONS = 3;
    /** StockX bids 子图偶发 "Failed to fetch from Subgraph 'bids'"，常持续数十秒，按退避重试。 */
    private static final long[] QUERY_RETRY_DELAYS_MS = {2_000L, 5_000L, 10_000L, 20_000L, 30_000L};
    private static final int MAX_QUERY_ATTEMPTS = QUERY_RETRY_DELAYS_MS.length + 1;
    private static final long NEXT_CHECK_DELAY_MS = 2_000L;

    private final StockXAccount account;
    private final Long taskId;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final Set<String> targetStyleIds;
    private final boolean deleteAll;
    private final Set<String> matchedStyleIds = new LinkedHashSet<>();
    private boolean firstTargetScanDone;
    private int progressDeleted;
    private int progressFailed;
    private int progressRound;
    private int progressObserved;

    public StockXDeleteBidsTaskRunner(StockXAccount account, Long taskId,
                                      StockXClient stockXClient, TaskMapper taskMapper,
                                      TaskItemMapper taskItemMapper) {
        this(account, taskId, Set.of(), stockXClient, taskMapper, taskItemMapper);
    }

    public StockXDeleteBidsTaskRunner(StockXAccount account, Long taskId,
                                      Set<String> targetStyleIds, StockXClient stockXClient,
                                      TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.stockXClient = stockXClient;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (targetStyleIds != null) {
            targetStyleIds.stream()
                    .map(StockXDeleteBidsTaskRunner::normalizeStyleId)
                    .filter(StrUtil::isNotBlank)
                    .forEach(normalized::add);
        }
        this.targetStyleIds = Set.copyOf(normalized);
        this.deleteAll = this.targetStyleIds.isEmpty();
    }

    @Override
    public void run() {
        String accountName = account.getName();
        TaskSwitch.setPurchaseRunning(accountName, StockXPurchaseOperation.DELETE_BIDS, true);
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isPurchaseCancelled(accountName, StockXPurchaseOperation.DELETE_BIDS),
                reason -> taskMapper.updateTaskFailReason(taskId, reason),
                () -> taskMapper.updateTaskFailReason(taskId, null));
        long start = System.currentTimeMillis();
        int round = 0;
        int deleted = 0;
        int failed = 0;
        int zeroConfirmations = 0;
        int noProgressRounds = 0;
        int total = 0;
        int scanned = 0;
        Set<String> observed = new HashSet<>();
        try {
            while (true) {
                ensureNotCancelled();
                progressDeleted = deleted;
                progressFailed = failed;
                progressRound = round;
                progressObserved = observed.size();
                BidScan scan = loadCurrentBids();
                List<JSONObject> bids = scan.bids();
                scanned = scan.scanned();
                int remaining = deleteAll ? scan.totalCount() : bids.size();
                total = Math.max(total, deleted + remaining);

                if (bids.isEmpty()) {
                    zeroConfirmations++;
                    String stage = zeroConfirmations >= ZERO_CONFIRMATIONS
                            ? "已完成"
                            : (deleteAll ? "确认清零 " : "确认目标清零 ")
                            + zeroConfirmations + "/" + ZERO_CONFIRMATIONS;
                    updateProgress(stage, total, 0, observed.size(), deleted, failed, round,
                            scanned, matchedStyleIds.size());
                    if (zeroConfirmations >= ZERO_CONFIRMATIONS) {
                        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
                        taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
                        taskMapper.updateTaskFailReason(taskId, null);
                        log.info("[{}] 撤销出价完成, taskId:{}, deleteMode:{}, deleted:{}, "
                                        + "failedAttempts:{}, matchedStyles:{}, rounds:{}",
                                accountName, taskId, deleteAll ? "all" : "style_ids",
                                deleted, failed, matchedStyleIds.size(), round);
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
                    updateProgress(deleteAll ? "撤销中" : "按货号撤销中", total,
                            Math.max(0, remaining - deletedThisRound),
                            observed.size(), deleted, failed, round, scanned, matchedStyleIds.size());
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
                    observed.size(), deleted, failed, round, scanned, matchedStyleIds.size());
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (StockXRateLimitException e) {
            taskMapper.updateTaskPaused(taskId, e.getMessage());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
            String reason = "TOKEN_EXPIRED".equals(e.getMessage())
                    ? "StockX Token已过期，请更新Token"
                    : StrUtil.blankToDefault(e.getMessage(), "撤销出价任务异常");
            taskMapper.updateTaskFailed(taskId, safeReason(reason));
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            log.error("[{}] 撤销出价任务异常, taskId:{}", accountName, taskId, e);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearPurchaseState(accountName, StockXPurchaseOperation.DELETE_BIDS);
        }
    }

    private BidScan loadCurrentBids() {
        if (deleteAll) {
            JSONObject page = loadPageWithRetry(null, null, 1);
            List<JSONObject> bids = extractBids(page);
            int totalCount = page.containsKey("totalCount")
                    ? Math.max(0, page.getIntValue("totalCount"))
                    : bids.size();
            return new BidScan(bids, totalCount, bids.size());
        }

        // 首轮检索全部目标货号；之后只复查曾匹配到出价的货号（未匹配的货号无需反复检索）。
        List<String> styles = new ArrayList<>(firstTargetScanDone ? matchedStyleIds : targetStyleIds);
        Map<String, JSONObject> matchingBids = new LinkedHashMap<>();
        int scanned = 0;
        for (int i = 0; i < styles.size(); i++) {
            String styleId = styles.get(i);
            Set<String> cursors = new HashSet<>();
            String after = null;
            int pageNumber = 1;
            while (true) {
                JSONObject page = loadPageWithRetry(styleId, after, pageNumber);
                List<JSONObject> bids = extractBids(page);
                scanned += bids.size();
                for (JSONObject bid : bids) {
                    // 搜索是服务端模糊匹配，这里仍按货号精确过滤
                    if (!styleId.equals(bidStyleId(bid))) {
                        continue;
                    }
                    matchedStyleIds.add(styleId);
                    matchingBids.putIfAbsent(bid.getString("id"), bid);
                }
                JSONObject pageInfo = page.getJSONObject("pageInfo");
                if (!pageInfo.getBooleanValue("hasNextPage")) {
                    break;
                }
                String nextCursor = StrUtil.trim(pageInfo.getString("endCursor"));
                if (StrUtil.isBlank(nextCursor) || !cursors.add(nextCursor)) {
                    throw new IllegalStateException("货号" + styleId + "的出价分页缺少新游标，已停止撤销");
                }
                after = nextCursor;
                pageNumber++;
            }
            if ((i + 1) % 10 == 0 && i + 1 < styles.size()) {
                updateProgress("检索货号 " + (i + 1) + "/" + styles.size(),
                        progressDeleted + matchingBids.size(), matchingBids.size(), progressObserved,
                        progressDeleted, progressFailed, progressRound, scanned, matchedStyleIds.size());
            }
        }
        firstTargetScanDone = true;
        return new BidScan(new ArrayList<>(matchingBids.values()), matchingBids.size(), scanned);
    }

    private JSONObject loadPageWithRetry(String styleId, String after, int pageNumber) {
        String pageLabel = (styleId == null ? "" : "货号" + styleId + "的") + "当前有效出价第" + pageNumber + "页";
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_QUERY_ATTEMPTS; attempt++) {
            ensureNotCancelled();
            try {
                JSONObject page = styleId == null
                        ? stockXClient.queryPurchasePage(StockXPurchaseOperation.BIDS, after, account)
                        : stockXClient.searchCurrentBidsPage(styleId, after, account);
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
                long delayMs = QUERY_RETRY_DELAYS_MS[attempt - 1];
                String message = "读取" + pageLabel + "失败，" + (delayMs / 1000)
                        + "秒后重试（" + attempt + "/" + (MAX_QUERY_ATTEMPTS - 1) + "）";
                taskMapper.updateTaskFailReason(taskId, message);
                log.warn("[{}] {}, taskId:{}", account.getName(), message, taskId);
                waitBeforeNextCheck(delayMs);
            }
        }
        throw new IllegalStateException("读取" + pageLabel + "失败（已重试"
                + (MAX_QUERY_ATTEMPTS - 1) + "次），已停止撤销", lastException);
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
                                int deleted, int failed, int round, int scanned,
                                int matchedStyleCount) {
        taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                .fluentPut("operation", StockXPurchaseOperation.DELETE_BIDS.getCode())
                .fluentPut("deleteMode", deleteAll ? "all" : "style_ids")
                .fluentPut("stage", stage)
                .fluentPut("total", total)
                .fluentPut("remaining", remaining)
                .fluentPut("processed", processed)
                .fluentPut("scanned", scanned)
                .fluentPut("deleted", deleted)
                .fluentPut("failed", failed)
                .fluentPut("targetStyleCount", targetStyleIds.size())
                .fluentPut("matchedStyleCount", matchedStyleCount)
                .fluentPut("unmatchedStyleCount", Math.max(0, targetStyleIds.size() - matchedStyleCount))
                .fluentPut("round", round)
                .toJSONString());
    }

    private String bidStyleId(JSONObject node) {
        JSONObject variant = node.getJSONObject("productVariant");
        JSONObject product = variant != null ? variant.getJSONObject("product") : null;
        return normalizeStyleId(product != null ? product.getString("styleId") : null);
    }

    private static String normalizeStyleId(String styleId) {
        String trimmed = StrUtil.trim(styleId);
        return StrUtil.isBlank(trimmed) ? "" : trimmed.toUpperCase(Locale.ROOT);
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
        if (TaskSwitch.isPurchaseCancelled(account.getName(), StockXPurchaseOperation.DELETE_BIDS) || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }

    private record BidScan(List<JSONObject> bids, int totalCount, int scanned) {
    }
}
