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

@Slf4j
public class StockXCreateBidsTaskRunner implements Runnable {

    private static final int BATCH_SIZE = 100;

    private final StockXAccount account;
    private final Long taskId;
    private final List<StockXBidInputExcel> inputRows;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXCreateBidsTaskRunner(StockXAccount account, Long taskId,
                                      List<StockXBidInputExcel> inputRows,
                                      StockXClient stockXClient, TaskMapper taskMapper,
                                      TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.inputRows = inputRows != null ? List.copyOf(inputRows) : List.of();
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
        try {
            Counters counters = execute();
            taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                    .fluentPut("operation", StockXPurchaseOperation.CREATE_BIDS.getCode())
                    .fluentPut("total", inputRows.size())
                    .fluentPut("submitted", counters.submitted)
                    .fluentPut("skipped", counters.skipped)
                    .fluentPut("failed", counters.failed)
                    .toJSONString());
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            taskMapper.updateTaskFailReason(taskId, "已提交" + counters.submitted
                    + "条，跳过" + counters.skipped + "条，失败" + counters.failed + "条");
        } catch (TaskCancelledException e) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (StockXRateLimitException e) {
            taskMapper.updateTaskPaused(taskId, e.getMessage());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
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

    private Counters execute() {
        ensureNotCancelled();
        Set<String> activeVariantIds = loadActiveBidVariantIds();
        Set<String> scheduledVariantIds = new HashSet<>();
        Map<String, List<StockXPriceExcel>> searchCache = new LinkedHashMap<>();
        List<PreparedBid> prepared = new ArrayList<>();
        Counters counters = new Counters();
        String country = StrUtil.blankToDefault(account.getCountry(), "US");

        for (StockXBidInputExcel input : inputRows) {
            ensureNotCancelled();
            String modelNo = input != null ? StrUtil.trim(input.getStyleId()) : null;
            String requestedSize = input != null ? StrUtil.trim(input.getSize()) : null;
            BigDecimal price = input != null ? input.getPrice() : null;
            String invalidReason = validateInput(modelNo, requestedSize, price);
            if (invalidReason != null) {
                insertFailure(modelNo, requestedSize, price, invalidReason);
                counters.failed++;
                continue;
            }

            String cacheKey = modelNo.toUpperCase(Locale.ROOT);
            List<StockXPriceExcel> candidates = searchCache.get(cacheKey);
            if (candidates == null) {
                candidates = stockXClient.searchExactItemWithPrice(
                        modelNo, "shoes", country, account);
                if (candidates == null) {
                    throw new IllegalStateException("TOKEN_EXPIRED");
                }
                searchCache.put(cacheKey, candidates);
            }
            StockXPriceExcel matched = findVariant(modelNo, requestedSize, candidates);
            if (matched == null || StrUtil.isBlank(matched.getId())) {
                insertFailure(modelNo, requestedSize, price, "未找到对应货号尺码");
                counters.failed++;
                continue;
            }

            String variantId = matched.getId().trim();
            TaskItemDO taskItem = buildTaskItem(modelNo, requestedSize, price, matched);
            if (activeVariantIds.contains(variantId)) {
                taskItem.setOperateResult("跳过-已有有效出价");
                taskItemMapper.insert(taskItem);
                counters.skipped++;
                continue;
            }
            if (!scheduledVariantIds.add(variantId)) {
                taskItem.setOperateResult("出价失败-Excel中尺码重复");
                taskItemMapper.insert(taskItem);
                counters.failed++;
                continue;
            }
            prepared.add(new PreparedBid(taskItem, new StockXBidCreateItem(
                    variantId, price.stripTrailingZeros(), localizedSizeType(requestedSize))));
        }

        for (int offset = 0; offset < prepared.size(); offset += BATCH_SIZE) {
            ensureNotCancelled();
            List<PreparedBid> batch = prepared.subList(offset, Math.min(offset + BATCH_SIZE, prepared.size()));
            try {
                StockXBidBatch result = stockXClient.createBids(
                        batch.stream().map(PreparedBid::request).toList(), account);
                for (PreparedBid bid : batch) {
                    bid.taskItem().setListingId(result.id());
                    bid.taskItem().setOrderStatus(result.status());
                    bid.taskItem().setOperateResult("出价已提交");
                    taskItemMapper.insert(bid.taskItem());
                    counters.submitted++;
                }
            } catch (StockXRateLimitException | TaskCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                    throw e;
                }
                String reason = StrUtil.blankToDefault(e.getMessage(), "StockX拒绝创建出价");
                reason = reason.substring(0, Math.min(reason.length(), 100));
                for (PreparedBid bid : batch) {
                    bid.taskItem().setOperateResult("出价失败-" + reason);
                    taskItemMapper.insert(bid.taskItem());
                    counters.failed++;
                }
            }
            int round = offset / BATCH_SIZE + 1;
            taskMapper.updateTaskRound(taskId, round);
        }
        return counters;
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

    private static final class Counters {
        private int submitted;
        private int skipped;
        private int failed;
    }
}
