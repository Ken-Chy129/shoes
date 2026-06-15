package cn.ken.shoes.task;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class StockXExcelDelistTaskRunner implements Runnable {

    private static final int BATCH_SIZE = 50;

    private final StockXAccount account;
    private final Long taskId;
    private final String inventoryType;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public StockXExcelDelistTaskRunner(StockXAccount account, Long taskId, String inventoryType,
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
        String accountId = account.getName();
        String key = accountId + ":" + inventoryType;
        TaskSwitch.setExcelDelistRunning(key, true);
        try {
            long startTime = System.currentTimeMillis();
            List<StockXDelistInputExcel> delistList = ShoesContext.getDelistList(accountId, inventoryType);
            int totalDelist = 0;
            int totalFailed = 0;
            int batchIndex = 0;

            for (int i = 0; i < delistList.size(); i += BATCH_SIZE) {
                if (TaskSwitch.isExcelDelistCancelled(key)) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                    taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(startTime));
                    log.info("[{}] Excel下架任务已取消", accountId);
                    return;
                }

                List<StockXDelistInputExcel> batch = delistList.subList(i, Math.min(i + BATCH_SIZE, delistList.size()));
                List<String> listingIds = new ArrayList<>();
                List<Long> taskItemIds = new ArrayList<>();

                for (StockXDelistInputExcel item : batch) {
                    TaskItemDO taskItemDO = new TaskItemDO();
                    taskItemDO.setTaskId(taskId);
                    taskItemDO.setRound(0);
                    taskItemDO.setListingId(item.getListingId());
                    taskItemDO.setStyleId(item.getStyleId());
                    taskItemDO.setSize(item.getSize());
                    taskItemMapper.insert(taskItemDO);

                    listingIds.add(item.getListingId());
                    taskItemIds.add(taskItemDO.getId());
                }

                boolean success = stockXClient.deleteItems(listingIds, account);
                String result = success ? "下架成功" : "下架失败";
                taskItemMapper.batchUpdateResult(taskItemIds, result);
                if (success) {
                    totalDelist += listingIds.size();
                } else {
                    totalFailed += listingIds.size();
                }

                batchIndex++;
                taskMapper.updateTaskRound(taskId, batchIndex);
            }

            String cost = TimeUtil.getCostMin(startTime);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, cost);
            String summary = "共" + delistList.size() + "条, 下架成功" + totalDelist + "条";
            if (totalFailed > 0) {
                summary += ", 失败" + totalFailed + "条";
            }
            taskMapper.updateTaskFailReason(taskId, summary);
            log.info("[{}] Excel下架任务完成, inventoryType:{}, total:{}, delist:{}, failed:{}, 耗时:{}",
                    accountId, inventoryType, delistList.size(), totalDelist, totalFailed, cost);
        } catch (Exception e) {
            log.error("[{}] Excel下架任务异常: {}", accountId, e.getMessage(), e);
            String reason = e.getMessage();
            if (reason != null && reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            taskMapper.updateTaskFailed(taskId, reason);
        } finally {
            TaskSwitch.clearExcelDelistState(key);
        }
    }
}
