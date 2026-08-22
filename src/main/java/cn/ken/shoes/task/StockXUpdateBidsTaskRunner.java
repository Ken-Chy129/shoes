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
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXUpdateBidsTaskRunner(StockXAccount account, Long taskId,
                                      List<StockXBidUpdateInputExcel> inputRows,
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
                    .fluentPut("operation", StockXPurchaseOperation.UPDATE_BIDS.getCode())
                    .fluentPut("total", inputRows.size())
                    .fluentPut("submitted", counters.submitted)
                    .fluentPut("failed", counters.failed)
                    .toJSONString());
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            taskMapper.updateTaskFailReason(taskId, "已提交" + counters.submitted
                    + "条，失败" + counters.failed + "条");
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

    private Counters execute() {
        Map<String, JSONObject> activeBids = loadActiveBids();
        List<PreparedBid> prepared = new ArrayList<>();
        Counters counters = new Counters();

        for (StockXBidUpdateInputExcel input : inputRows) {
            ensureNotCancelled();
            String bidId = input != null ? StrUtil.trim(input.getBidId()) : null;
            BigDecimal price = input != null ? input.getPrice() : null;
            String invalidReason = validateInput(bidId, price);
            if (invalidReason != null) {
                insertFailure(bidId, price, invalidReason);
                counters.failed++;
                continue;
            }
            JSONObject node = activeBids.get(bidId.toLowerCase(Locale.ROOT));
            if (node == null) {
                insertFailure(bidId, price, "未找到当前有效出价ID");
                counters.failed++;
                continue;
            }
            String activeBidId = node.getString("id").trim();
            TaskItemDO taskItem = StockXPurchaseItemConverter.convert(taskId, node,
                    StockXPurchaseOperation.BIDS);
            taskItem.setListingId(activeBidId);
            taskItem.setCurrentPrice(price.stripTrailingZeros());
            taskItem.setCurrencyCode(resolveMetadata(node, "currency", "currencyCode", "USD"));
            taskItem.setOperateTime(new Date());
            StockXBidUpdateItem request = new StockXBidUpdateItem(
                    activeBidId,
                    price.stripTrailingZeros(),
                    resolveMetadata(node, "deliveryOptionType", "effectiveDeliveryOptionType", "HOME_DELIVERY"),
                    taskItem.getCurrencyCode(),
                    resolveMetadata(node, "checkoutType", null, null));
            prepared.add(new PreparedBid(taskItem, request));
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
                    bid.taskItem().setOperateResult("修改出价已提交");
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
            taskMapper.updateTaskRound(taskId, offset / BATCH_SIZE + 1);
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

    private void insertFailure(String bidId, BigDecimal price, String reason) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);
        item.setListingId(bidId);
        item.setCurrentPrice(price);
        item.setCurrencyCode("USD");
        item.setOperateTime(new Date());
        item.setOperateResult("修改出价失败-" + reason);
        taskItemMapper.insert(item);
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isPurchaseCancelled(account.getName()) || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }

    private record PreparedBid(TaskItemDO taskItem, StockXBidUpdateItem request) {
    }

    private static final class Counters {
        private int submitted;
        private int failed;
    }
}
