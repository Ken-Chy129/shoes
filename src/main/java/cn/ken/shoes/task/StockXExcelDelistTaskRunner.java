package cn.ken.shoes.task;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isExcelDelistCancelled(key),
                reason -> taskMapper.updateTaskFailReason(taskId, reason));
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

                String batchId = null;
                String submitFailReason = null;
                try {
                    batchId = stockXClient.deleteItems(listingIds, account);
                } catch (StockXRateLimitException | TaskCancelledException e) {
                    throw e; // 限流冷却耗尽 / 取消：交给 runner 处理
                } catch (RuntimeException e) {
                    if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                        throw e; // Token 过期：终止任务
                    }
                    submitFailReason = e.getMessage() != null ? e.getMessage() : "下架失败";
                    if (submitFailReason.length() > 100) {
                        submitFailReason = submitFailReason.substring(0, 100);
                    }
                }
                if (submitFailReason != null) {
                    taskItemMapper.batchUpdateResult(taskItemIds, submitFailReason);
                    totalFailed += listingIds.size();
                } else {
                    // 已受理(QUEUED)，按 batchId 回查校验是否真正下架（成功=从结果消失）
                    Map<String, String> verify = stockXClient.verifyDeleteBatch(batchId, listingIds, account,
                            () -> TaskSwitch.isExcelDelistCancelled(key));
                    Map<String, List<Long>> resultToItemIds = new HashMap<>();
                    for (int j = 0; j < listingIds.size(); j++) {
                        String r = verify.getOrDefault(listingIds.get(j), "下架未确认");
                        resultToItemIds.computeIfAbsent(r, k -> new ArrayList<>()).add(taskItemIds.get(j));
                        if (r.startsWith("下架成功")) {
                            totalDelist++;
                        } else {
                            totalFailed++;
                        }
                    }
                    resultToItemIds.forEach((r, ids) -> taskItemMapper.batchUpdateResult(ids, r));
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
        } catch (TaskCancelledException ce) {
            log.info("[{}] Excel下架任务在限流冷却中被取消", accountId);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        } catch (Exception e) {
            if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                log.error("[{}] Excel下架任务因Token过期终止，请更新Token后重新启动", accountId);
                taskMapper.updateTaskFailed(taskId, "Token已过期，请更新Token");
            } else {
                log.error("[{}] Excel下架任务异常: {}", accountId, e.getMessage(), e);
                String reason = e.getMessage();
                if (reason != null && reason.length() > 200) {
                    reason = reason.substring(0, 200);
                }
                taskMapper.updateTaskFailed(taskId, reason);
            }
        } finally {
            // 限流/异常中断会留下已insert但未写结果的孤儿明细，统一标记，避免明细出现 null
            taskItemMapper.markPendingResult(taskId, "中断");
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearExcelDelistState(key);
        }
    }
}
