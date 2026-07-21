package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.TaskSwitch;
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

@Slf4j
public class StockXFetchOrdersTaskRunner implements Runnable {

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
            JSONObject result = stockXClient.queryOrderListings(category, pageNumber, account);
            JSONArray edges = requireEdges(result, category.getLabel(), pageNumber);
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
            JSONObject result = stockXClient.queryPendingAsks(after, account);
            JSONArray edges = requireEdges(result, StockXOrderCategory.PENDING.getLabel(), pages + 1);
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

    private JSONArray requireEdges(JSONObject result, String label, int pageNumber) {
        if (result == null) {
            throw new IllegalStateException(label + "第" + pageNumber + "页查询失败");
        }
        if (result.getBooleanValue("_unauthorized")) {
            throw new IllegalStateException("Token已过期，请更新Token");
        }
        JSONArray edges = result.getJSONArray("edges");
        if (edges == null) {
            throw new IllegalStateException(label + "响应缺少edges字段");
        }
        return edges;
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
