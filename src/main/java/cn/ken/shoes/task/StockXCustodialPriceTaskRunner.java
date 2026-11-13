package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.manager.StockXPriceUpdateCoordinator;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXCustodialPriceInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class StockXCustodialPriceTaskRunner implements Runnable {
    private final StockXAccount account;
    private final Long taskId;
    private final List<StockXCustodialPriceInputExcel> rows;
    private final StockXClient client;
    private final StockXPriceUpdateCoordinator coordinator;
    private final TaskMapper tasks;
    private final TaskItemMapper items;
    private final Map<String, TaskItemDO> details = new LinkedHashMap<>();

    @Override
    public void run() {
        long started = System.currentTimeMillis();
        StockXRateLimitGuard.beginTaskContext(account, this::cancelled,
                reason -> tasks.updateTaskFailReason(taskId, reason));
        try {
            Map<String, JSONObject> listings = loadListings();
            List<Map<String, String>> changes = new ArrayList<>();
            for (StockXCustodialPriceInputExcel row : rows) {
                checkCancelled();
                TaskItemDO item = detail(row, listings.get(row.getListingId()));
                items.insert(item);
                details.put(row.getListingId(), item);
                if ("待处理".equals(item.getOperateResult())) {
                    changes.add(Map.of("listingId", row.getListingId(),
                            "amount", row.getPrice().toPlainString(), "currencyCode", "USD"));
                }
            }
            for (int offset = 0; offset < changes.size(); offset += 100) {
                checkCancelled();
                List<Map<String, String>> batch = changes.subList(offset, Math.min(offset + 100, changes.size()));
                // 先持久化不确定状态，取消/异常不能把已发出的写请求标记成“未执行”。
                batch.forEach(change -> finish(details.get(change.get("listingId")), "未确认-提交中"));
                StockXPriceUpdateCoordinator.Submission submission = coordinator.submit(batch, account, this::cancelled,
                        (change, reason) -> finish(details.get(change.get("listingId")), "失败-StockX拒绝改价"),
                        reason -> tasks.updateTaskFailReason(taskId, reason),
                        () -> tasks.updateTaskFailReason(taskId, null));
                verify(submission.submittedItems());
                tasks.updateTaskRound(taskId, Math.min(offset + 100, changes.size()));
            }
            summarize();
        } catch (TaskCancelledException e) {
            markUnsent("未执行-已取消");
            tasks.updateTaskStatus(taskId, "cancel");
            tasks.updateTaskFailReason(taskId, "已停止后续请求；已提交的改价不能撤回，未确认项请重新获取寄存商品核对");
        } catch (Exception e) {
            markUnsent("未执行-任务中断");
            String reason = e.getMessage();
            tasks.updateTaskFailed(taskId, reason != null && (reason.contains("Token") || reason.contains("TOKEN_EXPIRED"))
                    ? "Token已过期，请更新Token；已提交项请核对实际价格"
                    : "寄存改价未完成：查询、提交或回查异常；已提交项请核对实际价格");
        } finally {
            try {
                tasks.updateTaskCost(taskId, TimeUtil.getCostMin(started));
            } finally {
                StockXRateLimitGuard.endTaskContext();
                TaskSwitch.clearCustodialPriceUpdate(account.getName());
            }
        }
    }

    private Map<String, JSONObject> loadListings() {
        Map<String, JSONObject> listings = new LinkedHashMap<>();
        for (int pageNumber = 1; ; pageNumber++) {
            checkCancelled();
            JSONObject page = client.queryCustodialPricePage(pageNumber, account);
            JSONObject pageInfo = page == null ? null : page.getJSONObject("pageInfo");
            JSONArray edges = page == null ? null : page.getJSONArray("edges");
            if (edges == null || pageInfo == null || !(pageInfo.get("hasNextPage") instanceof Boolean)
                    || (edges.isEmpty() && pageInfo.getBooleanValue("hasNextPage"))) {
                throw new IllegalStateException("寄存列表不完整");
            }
            for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                JSONObject node = edge == null ? null : edge.getJSONObject("node");
                String id = node == null ? null : node.getString("id");
                if (StrUtil.isBlank(id) || node.getBigDecimal("amount") == null
                        || !"USD".equals(node.getString("currency"))
                        || (node.containsKey("inventoryType") && !"CUSTODIAL".equals(node.getString("inventoryType")))
                        || listings.putIfAbsent(id, node) != null) {
                    throw new IllegalStateException("寄存列表商品、价格或分页无效");
                }
            }
            if (!pageInfo.getBooleanValue("hasNextPage")) return listings;
        }
    }

    private TaskItemDO detail(StockXCustodialPriceInputExcel row, JSONObject node) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setListingId(row.getListingId());
        item.setTargetPrice(row.getPrice());
        item.setCurrencyCode("USD");
        item.setOperateTime(new Date());
        if (node == null) {
            item.setOperateResult("失败-未找到所选账号的寄存挂单");
            return item;
        }
        item.setCurrentPrice(node.getBigDecimal("amount"));
        JSONObject variant = node.getJSONObject("productVariant");
        if (variant != null) {
            item.setProductId(variant.getString("id"));
            JSONObject traits = variant.getJSONObject("traits");
            if (traits != null) item.setSize(traits.getString("size"));
            JSONObject product = variant.getJSONObject("product");
            if (product != null) {
                item.setTitle(product.getString("title"));
                item.setStyleId(product.getString("styleId"));
            }
        }
        item.setOperateResult(item.getCurrentPrice().compareTo(row.getPrice()) == 0 ? "保持-价格相同" : "待处理");
        return item;
    }

    private void verify(List<Map<String, String>> submitted) {
        List<String> pending = new ArrayList<>(submitted.stream().map(change -> change.get("listingId")).toList());
        for (int attempt = 0; attempt < 3 && !pending.isEmpty(); attempt++) {
            checkCancelled();
            waitForSync();
            checkCancelled();
            Map<String, JSONObject> actual = client.verifyListingsByListingIds(List.copyOf(pending), account, true);
            if (actual == null) continue;
            for (String id : List.copyOf(pending)) {
                JSONObject node = actual.get(id);
                BigDecimal price = node == null ? null : node.getBigDecimal("amount");
                if (price != null && "USD".equals(node.getString("currency"))
                        && price.compareTo(details.get(id).getTargetPrice()) == 0) {
                    finish(details.get(id), "改价成功");
                    pending.remove(id);
                }
            }
        }
        pending.forEach(id -> finish(details.get(id), "未确认-已受理但未查到目标价格，请重新获取寄存商品核对"));
    }

    protected void waitForSync() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskCancelledException();
        }
    }

    private void finish(TaskItemDO item, String result) {
        item.setOperateResult(result);
        item.setOperateTime(new Date());
        items.updateById(item);
    }

    private void markUnsent(String result) {
        details.values().stream().filter(item -> "待处理".equals(item.getOperateResult()))
                .forEach(item -> finish(item, result));
    }

    private void summarize() {
        long success = count("改价成功");
        long skipped = count("保持");
        long failed = count("失败");
        long uncertain = count("未确认");
        String summary = "改价成功" + success + "，跳过" + skipped + "，失败" + failed + "，未确认" + uncertain;
        tasks.updateTaskAttributes(taskId, new JSONObject().fluentPut("success", success)
                .fluentPut("skipped", skipped).fluentPut("failed", failed).fluentPut("unconfirmed", uncertain).toJSONString());
        tasks.updateTaskRound(taskId, rows.size());
        if (failed > 0 || uncertain > 0) {
            tasks.updateTaskFailed(taskId, summary);
        } else {
            tasks.updateTaskStatus(taskId, "success");
            tasks.updateTaskFailReason(taskId, summary);
        }
    }

    private long count(String prefix) {
        return details.values().stream().filter(item -> item.getOperateResult().startsWith(prefix)).count();
    }

    private boolean cancelled() {
        return TaskSwitch.isCustodialPriceUpdateCancelled(account.getName()) || Thread.currentThread().isInterrupted();
    }

    private void checkCancelled() {
        if (cancelled()) throw new TaskCancelledException();
    }
}
