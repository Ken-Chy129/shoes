package cn.ken.shoes.task;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
public class StockXFetchListingsTaskRunner implements Runnable {

    private final StockXAccount account;
    private final Long taskId;
    private final String inventoryType;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXFetchListingsTaskRunner(StockXAccount account, Long taskId, String inventoryType,
                                          StockXClient stockXClient, TaskMapper taskMapper,
                                          TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.inventoryType = inventoryType;
        this.stockXClient = stockXClient;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    @Override
    public void run() {
        String key = account.getName() + ":" + inventoryType;
        TaskSwitch.setFetchListingsRunning(key, true);
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isFetchListingsCancelled(key),
                reason -> taskMapper.updateTaskFailReason(taskId, reason));
        try {
            long startTime = System.currentTimeMillis();
            int pageNumber = 1;
            boolean hasMore = true;
            int totalCount = 0;

            while (hasMore) {
                if (TaskSwitch.isFetchListingsCancelled(key)) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                    taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
                    log.info("[{}] 获取上架商品任务已取消", account.getName());
                    return;
                }

                JSONObject result = stockXClient.querySellingItemsByInventoryType(inventoryType, pageNumber, account);
                if (result == null) {
                    log.error("[{}] 获取上架商品失败, inventoryType:{}, page:{}", account.getName(), inventoryType, pageNumber);
                    taskMapper.updateTaskFailed(taskId, "查询第" + pageNumber + "页失败");
                    return;
                }
                if (result.getBooleanValue("_unauthorized")) {
                    taskMapper.updateTaskFailed(taskId, "Token已过期，请更新Token");
                    return;
                }

                List<JSONObject> items = result.getJSONArray("items").toJavaList(JSONObject.class);
                if (items.isEmpty()) {
                    break;
                }

                for (JSONObject item : items) {
                    TaskItemDO taskItemDO = new TaskItemDO();
                    taskItemDO.setTaskId(taskId);
                    taskItemDO.setRound(0);
                    taskItemDO.setListingId(item.getString("id"));
                    taskItemDO.setStyleId(item.getString("styleId"));
                    taskItemDO.setTitle(item.getString("productName"));
                    taskItemDO.setBrand(item.getString("brand"));
                    taskItemDO.setSize(item.getString("size"));
                    taskItemDO.setEuSize(item.getString("euSize"));
                    Integer amount = item.getInteger("amount");
                    taskItemDO.setCurrentPrice(amount != null ? BigDecimal.valueOf(amount) : null);
                    taskItemDO.setOperateResult("已获取");
                    taskItemMapper.insert(taskItemDO);
                }

                totalCount += items.size();
                hasMore = result.getBooleanValue("hasMore");
                pageNumber++;
                taskMapper.updateTaskRound(taskId, pageNumber - 1);
            }

            String cost = TimeUtil.getCostMin(startTime);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, cost);
            taskMapper.updateTaskFailReason(taskId, "共获取" + totalCount + "条商品");
            log.info("[{}] 获取上架商品任务完成, inventoryType:{}, total:{}, 耗时:{}", account.getName(), inventoryType, totalCount, cost);
        } catch (TaskCancelledException ce) {
            log.info("[{}] 获取上架商品任务在限流冷却中被取消", account.getName());
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        } catch (Exception e) {
            log.error("[{}] 获取上架商品任务异常: {}", account.getName(), e.getMessage(), e);
            String reason = e.getMessage();
            if (reason != null && reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            taskMapper.updateTaskFailed(taskId, reason);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearFetchListingsState(key);
        }
    }
}
