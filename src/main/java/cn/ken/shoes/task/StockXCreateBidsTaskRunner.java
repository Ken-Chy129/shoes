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
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.search.ModelNoSearchSizeFilter;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidBatch;
import cn.ken.shoes.model.stockx.StockXBidCreateItem;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class StockXCreateBidsTaskRunner implements Runnable {

    private static final int BATCH_SIZE = 100;
    private static final int DEFAULT_SEARCH_CONCURRENCY = 5;
    private static final long SEARCH_POLL_MS = 200L;

    private final StockXAccount account;
    private final Long taskId;
    private final List<StockXBidInputExcel> inputRows;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final int searchConcurrency;

    public StockXCreateBidsTaskRunner(StockXAccount account, Long taskId,
                                      List<StockXBidInputExcel> inputRows,
                                      StockXClient stockXClient, TaskMapper taskMapper,
                                      TaskItemMapper taskItemMapper) {
        this(account, taskId, inputRows, stockXClient, taskMapper, taskItemMapper,
                DEFAULT_SEARCH_CONCURRENCY);
    }

    public StockXCreateBidsTaskRunner(StockXAccount account, Long taskId,
                                      List<StockXBidInputExcel> inputRows,
                                      StockXClient stockXClient, TaskMapper taskMapper,
                                      TaskItemMapper taskItemMapper, int searchConcurrency) {
        this.account = account;
        this.taskId = taskId;
        this.inputRows = inputRows != null ? List.copyOf(inputRows) : List.of();
        this.stockXClient = stockXClient;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.searchConcurrency = Math.max(1, searchConcurrency);
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
        ProgressState progress = new ProgressState(inputRows.size());
        try {
            execute(progress);
            publishProgress(progress, "已完成");
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            taskMapper.updateTaskFailReason(taskId, "已提交" + progress.submitted
                    + "条，跳过" + progress.skipped + "条，失败" + progress.failed + "条");
        } catch (TaskCancelledException e) {
            markPendingSafely(progress, "未提交-任务已取消");
            publishProgressSafely(progress, "已取消");
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (StockXRateLimitException e) {
            markPendingSafely(progress, "未提交-任务已暂停");
            publishProgressSafely(progress, "已暂停");
            taskMapper.updateTaskPaused(taskId, e.getMessage());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
            markPendingSafely(progress, "未提交-任务失败");
            publishProgressSafely(progress, "执行失败");
            String reason = "TOKEN_EXPIRED".equals(e.getMessage())
                    ? "StockX Token已过期，请更新Token"
                    : StrUtil.blankToDefault(e.getMessage(), "创建出价任务异常");
            taskMapper.updateTaskFailed(taskId, reason.substring(0, Math.min(reason.length(), 200)));
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            log.error("[{}] 创建出价任务异常, taskId:{}", accountName, taskId, e);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearPurchaseState(accountName);
        }
    }

    private void execute(ProgressState progress) {
        ensureNotCancelled();
        Map<String, List<BidInput>> rowsByModel = groupValidInputs(progress);
        progress.modelTotal = rowsByModel.size();
        publishProgress(progress, "读取已有出价");

        Set<String> activeVariantIds = loadActiveBidVariantIds();
        Set<String> scheduledVariantIds = new HashSet<>();
        String country = StrUtil.blankToDefault(account.getCountry(), "US");
        ExecutorService executor = Executors.newFixedThreadPool(searchConcurrency,
                Thread.ofPlatform().daemon().name("StockX-Bid-Search-" + taskId + "-", 0).factory());
        List<SearchJob> jobs = new ArrayList<>();

        try {
            publishProgress(progress, "预处理");
            for (List<BidInput> modelRows : rowsByModel.values()) {
                BidInput first = modelRows.get(0);
                Future<List<StockXPriceExcel>> future = executor.submit(() -> {
                    ensureNotCancelled();
                    List<StockXPriceExcel> candidates = stockXClient.searchExactItemWithPrice(
                            first.modelNo(), "shoes", country, account);
                    if (candidates == null) {
                        throw new IllegalStateException("TOKEN_EXPIRED");
                    }
                    ensureNotCancelled();
                    return candidates;
                });
                jobs.add(new SearchJob(modelRows, future));
            }

            for (SearchJob job : jobs) {
                List<StockXPriceExcel> candidates = awaitSearch(job.future());
                for (BidInput input : job.rows()) {
                    prepareBid(input, candidates, activeVariantIds, scheduledVariantIds, progress);
                }
                progress.modelsResolved++;
                progress.processed += job.rows().size();
                while (progress.pendingBids.size() >= BATCH_SIZE) {
                    submitNextBatch(progress, BATCH_SIZE);
                }
                publishProgress(progress, "预处理");
            }
            if (!progress.pendingBids.isEmpty()) {
                submitNextBatch(progress, progress.pendingBids.size());
            }
        } finally {
            for (SearchJob job : jobs) {
                if (!job.future().isDone()) {
                    job.future().cancel(true);
                }
            }
            executor.shutdownNow();
        }
    }

    private Map<String, List<BidInput>> groupValidInputs(ProgressState progress) {
        Map<String, List<BidInput>> rowsByModel = new LinkedHashMap<>();
        for (StockXBidInputExcel input : inputRows) {
            ensureNotCancelled();
            String modelNo = input != null ? StrUtil.trim(input.getStyleId()) : null;
            String requestedSize = input != null ? StrUtil.trim(input.getSize()) : null;
            BigDecimal price = input != null ? input.getPrice() : null;
            String invalidReason = validateInput(modelNo, requestedSize, price);
            if (invalidReason != null) {
                insertFailure(modelNo, requestedSize, price, invalidReason);
                progress.failed++;
                progress.processed++;
                continue;
            }
            String cacheKey = modelNo.toUpperCase(Locale.ROOT);
            rowsByModel.computeIfAbsent(cacheKey, ignored -> new ArrayList<>())
                    .add(new BidInput(modelNo, requestedSize, price));
        }
        return rowsByModel;
    }

    private List<StockXPriceExcel> awaitSearch(Future<List<StockXPriceExcel>> future) {
        while (true) {
            ensureNotCancelled();
            try {
                return future.get(SEARCH_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // 分片等待，确保只设置取消标志也能及时终止任务及查询 worker。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("查询货号失败", cause);
            }
        }
    }

    private void prepareBid(BidInput input, List<StockXPriceExcel> candidates,
                            Set<String> activeVariantIds, Set<String> scheduledVariantIds,
                            ProgressState progress) {
        StockXPriceExcel matched = findVariant(input.modelNo(), input.size(), candidates);
        if (matched == null || StrUtil.isBlank(matched.getId())) {
            insertFailure(input.modelNo(), input.size(), input.price(), "未找到对应货号尺码");
            progress.failed++;
            return;
        }

        String variantId = matched.getId().trim();
        TaskItemDO taskItem = buildTaskItem(input.modelNo(), input.size(), input.price(), matched);
        if (activeVariantIds.contains(variantId)) {
            taskItem.setOperateResult("跳过-已有有效出价");
            taskItemMapper.insert(taskItem);
            progress.skipped++;
            return;
        }
        if (!scheduledVariantIds.add(variantId)) {
            taskItem.setOperateResult("出价失败-Excel中尺码重复");
            taskItemMapper.insert(taskItem);
            progress.failed++;
            return;
        }

        taskItem.setOperateResult("待提交出价");
        int inserted = taskItemMapper.insert(taskItem);
        if (inserted != 1 || taskItem.getId() == null) {
            throw new IllegalStateException("保存待提交出价明细失败，已停止创建出价");
        }
        progress.pendingBids.add(new PreparedBid(taskItem, new StockXBidCreateItem(
                variantId, input.price().stripTrailingZeros(), localizedSizeType(input.size()))));
    }

    private void submitNextBatch(ProgressState progress, int size) {
        ensureNotCancelled();
        List<PreparedBid> batch = new ArrayList<>(progress.pendingBids.subList(0, size));
        publishProgress(progress, "提交第" + (progress.batches + 1) + "批");
        StockXBidBatch result;
        try {
            result = stockXClient.createBids(
                    batch.stream().map(PreparedBid::request).toList(), account);
        } catch (StockXRateLimitException | TaskCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                throw e;
            }
            String reason = StrUtil.blankToDefault(e.getMessage(), "StockX拒绝创建出价");
            reason = reason.substring(0, Math.min(reason.length(), 100));
            progress.pendingBids.subList(0, size).clear();
            progress.failed += batch.size();
            for (PreparedBid bid : batch) {
                bid.taskItem().setOperateResult("出价失败-" + reason);
                bid.taskItem().setOperateTime(new Date());
            }
            updateBatchItems(batch, "保存出价失败结果");
            finishBatch(progress);
            return;
        }

        // StockX 已接受整批请求，此时不能再把这些明细当作“未提交”清理。
        progress.pendingBids.subList(0, size).clear();
        progress.submitted += batch.size();
        for (PreparedBid bid : batch) {
            bid.taskItem().setListingId(result.id());
            bid.taskItem().setOrderStatus(result.status());
            bid.taskItem().setOperateResult("出价已提交");
            bid.taskItem().setOperateTime(new Date());
        }
        updateBatchItems(batch, "保存已提交出价结果");
        finishBatch(progress);
    }

    private void finishBatch(ProgressState progress) {
        progress.batches++;
        taskMapper.updateTaskRound(taskId, progress.batches);
        publishProgress(progress, "预处理");
    }

    private void updateBatchItems(List<PreparedBid> batch, String action) {
        RuntimeException firstFailure = null;
        for (PreparedBid bid : batch) {
            try {
                updateTaskItem(bid.taskItem(), action);
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.error("[{}] {}, taskId:{}, taskItemId:{}", account.getName(), action,
                        taskId, bid.taskItem().getId(), e);
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void updateTaskItem(TaskItemDO item, String action) {
        if (item.getId() == null || taskItemMapper.updateById(item) != 1) {
            throw new IllegalStateException(action + "失败，taskItemId=" + item.getId());
        }
    }

    private void markPendingSafely(ProgressState progress, String result) {
        for (PreparedBid bid : progress.pendingBids) {
            bid.taskItem().setOperateResult(result);
            bid.taskItem().setOperateTime(new Date());
            try {
                updateTaskItem(bid.taskItem(), "清理未提交出价明细");
            } catch (RuntimeException e) {
                log.error("[{}] 清理未提交出价明细失败, taskId:{}, taskItemId:{}",
                        account.getName(), taskId, bid.taskItem().getId(), e);
            }
        }
        progress.pendingBids.clear();
    }

    private void publishProgressSafely(ProgressState progress, String stage) {
        try {
            publishProgress(progress, stage);
        } catch (RuntimeException e) {
            log.error("[{}] 更新创建出价任务进度失败, taskId:{}, stage:{}",
                    account.getName(), taskId, stage, e);
        }
    }

    private void publishProgress(ProgressState progress, String stage) {
        taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                .fluentPut("operation", StockXPurchaseOperation.CREATE_BIDS.getCode())
                .fluentPut("stage", stage)
                .fluentPut("total", progress.total)
                .fluentPut("processed", progress.processed)
                .fluentPut("modelTotal", progress.modelTotal)
                .fluentPut("modelsResolved", progress.modelsResolved)
                .fluentPut("pending", progress.pendingBids.size())
                .fluentPut("submitted", progress.submitted)
                .fluentPut("skipped", progress.skipped)
                .fluentPut("failed", progress.failed)
                .fluentPut("batches", progress.batches)
                .toJSONString());
    }

    private Set<String> loadActiveBidVariantIds() {
        Set<String> result = new HashSet<>();
        Set<String> seenCursors = new HashSet<>();
        String after = null;
        while (true) {
            ensureNotCancelled();
            JSONObject page = stockXClient.queryPurchasePage(StockXPurchaseOperation.BIDS, after, account);
            if (page != null && page.getBooleanValue("_unauthorized")) {
                throw new IllegalStateException("TOKEN_EXPIRED");
            }
            if (page == null || page.getJSONArray("edges") == null || page.getJSONObject("pageInfo") == null) {
                throw new IllegalStateException("读取已有有效出价失败，已停止创建以避免重复出价");
            }
            for (JSONObject edge : page.getJSONArray("edges").toJavaList(JSONObject.class)) {
                JSONObject node = edge.getJSONObject("node");
                JSONObject variant = node != null ? node.getJSONObject("productVariant") : null;
                if (variant != null && StrUtil.isNotBlank(variant.getString("id"))) {
                    result.add(variant.getString("id").trim());
                }
            }
            JSONObject pageInfo = page.getJSONObject("pageInfo");
            if (!pageInfo.getBooleanValue("hasNextPage")) {
                return result;
            }
            String nextCursor = pageInfo.getString("endCursor");
            if (StrUtil.isBlank(nextCursor) || !seenCursors.add(nextCursor)) {
                throw new IllegalStateException("读取已有有效出价时分页游标无效");
            }
            after = nextCursor;
        }
    }

    private StockXPriceExcel findVariant(String modelNo, String requestedSize,
                                          List<StockXPriceExcel> candidates) {
        ModelNoSearchExcel filterRow = new ModelNoSearchExcel();
        filterRow.setModelNo(candidates.stream().map(StockXPriceExcel::getModelNo)
                .filter(StrUtil::isNotBlank).findFirst().orElse(modelNo));
        filterRow.setSize(requestedSize);
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(filterRow));
        return candidates.stream().filter(item -> ModelNoSearchSizeFilter.matches(
                        filters, item.getModelNo(), item.getUsmSize(), item.getUswSize(), item.getEuSize()))
                .findFirst().orElse(null);
    }

    private TaskItemDO buildTaskItem(String modelNo, String requestedSize, BigDecimal price,
                                     StockXPriceExcel matched) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);
        item.setProductId(matched.getId());
        item.setBrand(matched.getBrand());
        item.setTitle(matched.getTitle());
        item.setStyleId(StrUtil.blankToDefault(matched.getModelNo(), modelNo));
        item.setSize(resolveUsSize(requestedSize, matched));
        item.setEuSize(matched.getEuSize());
        item.setCurrentPrice(price);
        item.setCurrencyCode("USD");
        item.setOperateTime(new Date());
        return item;
    }

    private void insertFailure(String modelNo, String size, BigDecimal price, String reason) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);
        item.setStyleId(modelNo);
        item.setSize(size);
        item.setCurrentPrice(price);
        item.setCurrencyCode("USD");
        item.setOperateTime(new Date());
        item.setOperateResult("出价失败-" + reason);
        taskItemMapper.insert(item);
    }

    private String resolveUsSize(String requestedSize, StockXPriceExcel matched) {
        if (ModelNoSearchSizeFilter.isWomenSize(requestedSize) && StrUtil.isNotBlank(matched.getUswSize())) {
            return matched.getUswSize();
        }
        return StrUtil.blankToDefault(matched.getUsmSize(), requestedSize);
    }

    private String validateInput(String modelNo, String size, BigDecimal price) {
        if (StrUtil.isBlank(modelNo)) {
            return "货号必填";
        }
        if (StrUtil.isBlank(size)) {
            return "尺码必填";
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return "价格必须大于0";
        }
        if (price.stripTrailingZeros().scale() > 0) {
            return "价格必须为整数美元";
        }
        return null;
    }

    public static String localizedSizeType(String size) {
        String normalized = ShoesUtil.normalizeUnicodeFraction(
                        StrUtil.blankToDefault(size, "").trim().toUpperCase(Locale.ROOT))
                .replaceAll("\\s+", "");
        if (normalized.startsWith("EU")) {
            return "eu";
        }
        if (normalized.startsWith("USW") || normalized.startsWith("W") || normalized.endsWith("W")) {
            return "us w";
        }
        return "us m";
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isPurchaseCancelled(account.getName()) || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }

    private record PreparedBid(TaskItemDO taskItem, StockXBidCreateItem request) {
    }

    private record BidInput(String modelNo, String size, BigDecimal price) {
    }

    private record SearchJob(List<BidInput> rows, Future<List<StockXPriceExcel>> future) {
    }

    private static final class ProgressState {
        private final int total;
        private final List<PreparedBid> pendingBids = new ArrayList<>();
        private int processed;
        private int modelTotal;
        private int modelsResolved;
        private int batches;
        private int submitted;
        private int skipped;
        private int failed;

        private ProgressState(int total) {
            this.total = total;
        }
    }
}
