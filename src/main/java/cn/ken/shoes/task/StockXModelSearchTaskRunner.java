package cn.ken.shoes.task;

import cn.ken.shoes.common.ModelSearchOperation;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class StockXModelSearchTaskRunner implements Runnable {

    private final StockXAccount account;
    private final Long taskId;
    private final ModelSearchOperation operation;
    private final List<ModelNoSearchExcel> priceRows;
    private final List<ModelSearchListingExcel> listingRows;
    private final StockXService stockXService;
    private final TaskMapper taskMapper;

    public StockXModelSearchTaskRunner(StockXAccount account, Long taskId, ModelSearchOperation operation,
                                       List<ModelNoSearchExcel> priceRows,
                                       List<ModelSearchListingExcel> listingRows,
                                       StockXService stockXService, TaskMapper taskMapper) {
        this.account = account;
        this.taskId = taskId;
        this.operation = operation;
        this.priceRows = priceRows != null ? List.copyOf(priceRows) : List.of();
        this.listingRows = listingRows != null ? List.copyOf(listingRows) : List.of();
        this.stockXService = stockXService;
        this.taskMapper = taskMapper;
    }

    @Override
    public void run() {
        String accountName = account.getName();
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isSearchListCancelled(accountName),
                reason -> taskMapper.updateTaskFailReason(taskId, reason));
        long start = System.currentTimeMillis();
        try {
            if (operation == ModelSearchOperation.FETCH_PRICE) {
                stockXService.fetchModelSearchPrices(account, taskId, priceRows);
            } else {
                stockXService.createModelSearchListings(account, taskId, listingRows);
            }
            if (TaskSwitch.isSearchListCancelled(accountName)) {
                TaskSwitch.cancelSearchVerification(taskId);
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            } else {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            }
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (TaskCancelledException e) {
            TaskSwitch.cancelSearchVerification(taskId);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        } catch (StockXRateLimitException e) {
            TaskSwitch.cancelSearchVerification(taskId);
            taskMapper.updateTaskPaused(taskId, e.getMessage());
        } catch (Exception e) {
            TaskSwitch.cancelSearchVerification(taskId);
            String reason = e.getMessage();
            if (reason != null && reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            taskMapper.updateTaskFailed(taskId, reason);
            log.error("[{}] 货号搜索任务异常, operation:{}", accountName, operation.getCode(), e);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearSearchListRunState(accountName);
        }
    }
}
