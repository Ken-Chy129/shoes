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
import cn.ken.shoes.model.excel.StockXBidUpdateInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidBatch;
import cn.ken.shoes.model.stockx.StockXBidUpdateItem;
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
public class StockXUpdateBidsTaskRunner implements Runnable {

    private static final int BATCH_SIZE = 50;

    private final StockXAccount account;
    private final Long taskId;
    private final List<StockXBidUpdateInputExcel> inputRows;
    private final long intervalSeconds;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXUpdateBidsTaskRunner(StockXAccount account, Long taskId,
                                      List<StockXBidUpdateInputExcel> inputRows,
                                      long intervalSeconds,
                                      StockXClient stockXClient, TaskMapper taskMapper,
                                      TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.inputRows = inputRows != null ? List.copyOf(inputRows) : List.of();
        this.intervalSeconds = intervalSeconds;
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
            Counters totals = new Counters();
            int round = 0;
            while (true) {
                ensureNotCancelled();
                round++;
                Counters current = executeRound(round);
                totals.add(current);
                taskMapper.updateTaskRound(taskId, round);
                taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                        .fluentPut("operation", StockXPurchaseOperation.UPDATE_BIDS.getCode())
                        .fluentPut("total", inputRows.size())
                        .fluentPut("interval", intervalSeconds)
                        .fluentPut("round", round)
                        .fluentPut("submitted", totals.submitted)
                        .fluentPut("highest", current.highest)
                        .fluentPut("capped", current.capped)
                        .fluentPut("failed", totals.failed)
                        .toJSONString());
                taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
                taskMapper.updateTaskFailReason(taskId, "第" + round + "轮：追价" + current.submitted
                        + "条，已是最高" + current.highest + "条，达到上限" + current.capped
                        + "条，失败" + current.failed + "条");
                waitBeforeNextRound(intervalSeconds * 1000L);
            }
        } catch (TaskCancelledException e) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (StockXRateLimitException e) {
            taskMapper.updateTaskPaused(taskId, e.getMessage());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
            String reason = "TOKEN_EXPIRED".equals(e.getMessage())
                    ? "StockX Token已过期，请更新Token"
                    : StrUtil.blankToDefault(e.getMessage(), "修改出价任务异常");
            taskMapper.updateTaskFailed(taskId, reason.substring(0, Math.min(reason.length(), 200)));
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            log.error("[{}] 修改出价任务异常, taskId:{}", accountName, taskId, e);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearPurchaseState(accountName);
        }
    }

    private Counters executeRound(int round) {
        Map<String, JSONObject> activeBids = loadActiveBids();
        List<PreparedBid> prepared = new ArrayList<>();
        Counters counters = new Counters();

        for (StockXBidUpdateInputExcel input : inputRows) {
            ensureNotCancelled();
            String bidId = input != null ? StrUtil.trim(input.getBidId()) : null;
            BigDecimal price = input != null ? input.getPrice() : null;
            String invalidReason = validateInput(bidId, price);
            if (invalidReason != null) {
                insertFailure(round, bidId, price, invalidReason);
                counters.failed++;
                continue;
            }
            JSONObject node = activeBids.get(bidId.toLowerCase(Locale.ROOT));
            if (node == null) {
                insertFailure(round, bidId, price, "未找到当前有效出价ID");
                counters.failed++;
                continue;
            }
            String activeBidId = node.getString("id").trim();
            TaskItemDO taskItem = StockXPurchaseItemConverter.convert(taskId, node,
                    StockXPurchaseOperation.BIDS);
            taskItem.setRound(round);
            taskItem.setListingId(activeBidId);
            BigDecimal currentBid = decimal(node.get("amount"));
            BigDecimal highestBid = highestBid(node);
            BigDecimal maximumPrice = price.stripTrailingZeros();
            taskItem.setCurrentPrice(currentBid);
            taskItem.setLowestPrice(highestBid);
            taskItem.setTargetPrice(maximumPrice);
            taskItem.setCurrencyCode(resolveMetadata(node, "currency", "currencyCode", "USD"));
            taskItem.setOperateTime(new Date());
            if (currentBid == null || highestBid == null) {
                taskItem.setOrderStatus("数据异常");
                taskItem.setOperateResult("修改出价失败-缺少当前出价或市场最高价");
                taskItemMapper.insert(taskItem);
                counters.failed++;
                continue;
            }
            if (currentBid.compareTo(highestBid) >= 0) {
                taskItem.setOrderStatus("最高出价");
                taskItem.setOperateResult("已是最高出价($" + money(currentBid)
                        + "，上限$" + money(maximumPrice) + ")");
                taskItemMapper.insert(taskItem);
                counters.highest++;
                continue;
            }
            BigDecimal nextBid = highestBid.add(BigDecimal.ONE);
            if (nextBid.compareTo(maximumPrice) > 0) {
                taskItem.setOrderStatus("达到上限");
                taskItem.setOperateResult("已达最高价上限(市场$" + money(highestBid)
                        + "，上限$" + money(maximumPrice) + ")");
                taskItemMapper.insert(taskItem);
                counters.capped++;
                continue;
            }
            StockXBidUpdateItem request = new StockXBidUpdateItem(
                    activeBidId,
                    nextBid,
                    resolveMetadata(node, "deliveryOptionType", "effectiveDeliveryOptionType", "HOME_DELIVERY"),
                    taskItem.getCurrencyCode(),
                    resolveMetadata(node, "checkoutType", null, null));
            prepared.add(new PreparedBid(taskItem, request, maximumPrice));
        }

        for (int offset = 0; offset < prepared.size(); offset += BATCH_SIZE) {
            ensureNotCancelled();
            List<PreparedBid> batch = prepared.subList(offset, Math.min(offset + BATCH_SIZE, prepared.size()));
            try {
                StockXBidBatch result = stockXClient.updateBids(
                        batch.stream().map(PreparedBid::request).toList(), account);
                for (PreparedBid bid : batch) {
                    bid.taskItem().setOrderNumber(result.id());
                    bid.taskItem().setOrderStatus(result.status());
                    bid.taskItem().setOperateResult("追价已提交($" + money(bid.request().amount())
                            + "，上限$" + money(bid.maximumPrice()) + ")");
                    taskItemMapper.insert(bid.taskItem());
                    counters.submitted++;
                }
            } catch (StockXRateLimitException | TaskCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                    throw e;
                }
                String reason = StrUtil.blankToDefault(e.getMessage(), "StockX拒绝修改出价");
                reason = reason.substring(0, Math.min(reason.length(), 100));
                for (PreparedBid bid : batch) {
                    bid.taskItem().setOperateResult("修改出价失败-" + reason);
                    taskItemMapper.insert(bid.taskItem());
                    counters.failed++;
                }
            }
        }
        return counters;
    }

    private Map<String, JSONObject> loadActiveBids() {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        String after = null;
        while (true) {
            ensureNotCancelled();
            JSONObject page = stockXClient.queryPurchasePage(StockXPurchaseOperation.BIDS, after, account);
            if (page != null && page.getBooleanValue("_unauthorized")) {
                throw new IllegalStateException("TOKEN_EXPIRED");
            }
            if (page == null || page.getJSONArray("edges") == null || page.getJSONObject("pageInfo") == null) {
                throw new IllegalStateException("读取当前有效出价失败，已停止修改");
            }
            for (JSONObject edge : page.getJSONArray("edges").toJavaList(JSONObject.class)) {
                JSONObject node = edge.getJSONObject("node");
                if (node != null && StrUtil.isNotBlank(node.getString("id"))) {
                    result.put(node.getString("id").trim().toLowerCase(Locale.ROOT), node);
                }
            }
            JSONObject pageInfo = page.getJSONObject("pageInfo");
            if (!pageInfo.getBooleanValue("hasNextPage")) {
                return result;
            }
            String nextCursor = pageInfo.getString("endCursor");
            if (StrUtil.isBlank(nextCursor) || !seenCursors.add(nextCursor)) {
                throw new IllegalStateException("读取当前有效出价时分页游标无效");
            }
            after = nextCursor;
        }
    }

    private String resolveMetadata(JSONObject node, String primaryKey, String secondaryKey,
                                   String defaultValue) {
        String value = node.getString(primaryKey);
        JSONObject details = node.getJSONObject("productDetails");
        if (StrUtil.isBlank(value) && details != null) {
            value = details.getString(primaryKey);
        }
        if (StrUtil.isBlank(value) && secondaryKey != null) {
            value = node.getString(secondaryKey);
            if (StrUtil.isBlank(value) && details != null) {
                value = details.getString(secondaryKey);
            }
        }
        return StrUtil.blankToDefault(value, defaultValue);
    }

    private String validateInput(String bidId, BigDecimal price) {
        if (StrUtil.isBlank(bidId)) return "出价ID必填";
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) return "价格必须大于0";
        if (price.stripTrailingZeros().scale() > 0) return "价格必须为整数美元";
        return null;
    }

    private BigDecimal highestBid(JSONObject node) {
        JSONObject variant = node.getJSONObject("productVariant");
        JSONObject market = variant != null ? variant.getJSONObject("market") : null;
        JSONObject state = market != null ? market.getJSONObject("state") : null;
        JSONObject inventoryTypes = state != null ? state.getJSONObject("bidInventoryTypes") : null;
        JSONObject standard = inventoryTypes != null ? inventoryTypes.getJSONObject("standard") : null;
        JSONObject highest = standard != null ? standard.getJSONObject("highest") : null;
        return highest != null ? decimal(highest.get("amount")) : null;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String money(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void insertFailure(int round, String bidId, BigDecimal price, String reason) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(round);
        item.setListingId(bidId);
        item.setTargetPrice(price);
        item.setCurrencyCode("USD");
        item.setOperateTime(new Date());
        item.setOperateResult("修改出价失败-" + reason);
        taskItemMapper.insert(item);
    }

    protected void waitBeforeNextRound(long delayMs) {
        long remaining = delayMs;
        while (remaining > 0) {
            ensureNotCancelled();
            long sleepMs = Math.min(remaining, 1000L);
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

    private record PreparedBid(TaskItemDO taskItem, StockXBidUpdateItem request,
                               BigDecimal maximumPrice) {
    }

    private static final class Counters {
        private int submitted;
        private int highest;
        private int capped;
        private int failed;

        private void add(Counters other) {
            submitted += other.submitted;
            highest += other.highest;
            capped += other.capped;
            failed += other.failed;
        }
    }
}
