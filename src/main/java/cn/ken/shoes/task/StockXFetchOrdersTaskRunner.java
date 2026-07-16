package cn.ken.shoes.task;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class StockXFetchOrdersTaskRunner implements Runnable {

    private final StockXAccount account;
    private final Long taskId;
    private final List<StockXOrderCategory> categories;
    private final boolean fetchPayout;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXFetchOrdersTaskRunner(StockXAccount account, Long taskId,
                                       List<StockXOrderCategory> categories, boolean fetchPayout,
                                       StockXClient stockXClient, TaskMapper taskMapper,
                                       TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.categories = categories;
        this.fetchPayout = fetchPayout;
        this.stockXClient = stockXClient;
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
                int categoryCount = 0;
                int pageNumber = 1;
                boolean hasNextPage;
                do {
                    if (cancelled()) {
                        cancelTask(startTime);
                        return;
                    }
                    JSONObject result = stockXClient.queryOrderListings(category, pageNumber, account);
                    if (result == null) {
                        taskMapper.updateTaskFailed(taskId, category.getLabel() + "第" + pageNumber + "页查询失败");
                        return;
                    }
                    if (result.getBooleanValue("_unauthorized")) {
                        taskMapper.updateTaskFailed(taskId, "Token已过期，请更新Token");
                        return;
                    }
                    JSONArray edges = result.getJSONArray("edges");
                    if (edges == null) {
                        taskMapper.updateTaskFailed(taskId, category.getLabel() + "响应缺少edges字段");
                        return;
                    }
                    for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                        if (cancelled()) {
                            cancelTask(startTime);
                            return;
                        }
                        JSONObject node = edge.getJSONObject("node");
                        if (node == null) {
                            continue;
                        }
                        BigDecimal payout = fetchPayout
                                ? stockXClient.queryOrderPayout(node.getString("id"), account)
                                : null;
                        taskItemMapper.insert(StockXOrderItemConverter.convert(taskId, node, category, payout));
                        categoryCount++;
                    }
                    JSONObject pageInfo = result.getJSONObject("pageInfo");
                    hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
                    pageNumber++;
                    totalPages++;
                    taskMapper.updateTaskRound(taskId, totalPages);
                } while (hasNextPage);
                counts.put(category.getCode(), categoryCount);
                totalOrders += categoryCount;
                taskMapper.updateTaskAttributes(taskId, new JSONObject()
                        .fluentPut("counts", counts)
                        .fluentPut("total", totalOrders)
                        .fluentPut("fetchPayout", fetchPayout)
                        .toJSONString());
            }

            String cost = TimeUtil.getCostMin(startTime);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, cost);
            taskMapper.updateTaskFailReason(taskId, "共获取" + totalOrders + "条订单");
            log.info("[{}] 获取订单任务完成, categories:{}, total:{}, fetchPayout:{}, 耗时:{}",
                    account.getName(), categories, totalOrders, fetchPayout, cost);
        } catch (Exception e) {
            log.error("[{}] 获取订单任务异常: {}", account.getName(), e.getMessage(), e);
            String reason = e.getMessage();
            if (reason != null && reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            taskMapper.updateTaskFailed(taskId, reason != null ? reason : "未知异常");
        } finally {
            TaskSwitch.clearFetchOrdersState(account.getName());
        }
    }

    private boolean cancelled() {
        return TaskSwitch.isFetchOrdersCancelled(account.getName());
    }

    private void cancelTask(long startTime) {
        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
        log.info("[{}] 获取订单任务已取消", account.getName());
    }
}
