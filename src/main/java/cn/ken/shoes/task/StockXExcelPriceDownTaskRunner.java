package cn.ken.shoes.task;

import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StockXExcelPriceDownTaskRunner implements Runnable {

    private final StockXAccount account;
    private final String inventoryType;
    private final ListingFetchMode fetchMode;
    private final StockXService stockXService;
    private final TaskMapper taskMapper;

    public StockXExcelPriceDownTaskRunner(StockXAccount account, String inventoryType,
                                          StockXService stockXService, TaskMapper taskMapper) {
        this(account, inventoryType, ListingFetchMode.ALL, stockXService, taskMapper);
    }

    public StockXExcelPriceDownTaskRunner(StockXAccount account, String inventoryType, ListingFetchMode fetchMode,
                                          StockXService stockXService, TaskMapper taskMapper) {
        this.account = account;
        this.inventoryType = inventoryType;
        this.fetchMode = fetchMode;
        this.stockXService = stockXService;
        this.taskMapper = taskMapper;
    }

    @Override
    public void run() {
        String accountId = account.getName();
        TaskSwitch.setExcelRunning(accountId, inventoryType, true);
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isExcelCancelled(accountId, inventoryType),
                reason -> {
                    Long tid = TaskSwitch.getExcelTaskId(accountId, inventoryType);
                    if (tid != null) {
                        taskMapper.updateTaskFailReason(tid, reason);
                    }
                },
                () -> {
                    // 从限流冷却恢复：清除"冷却中"提示
                    Long tid = TaskSwitch.getExcelTaskId(accountId, inventoryType);
                    if (tid != null) {
                        taskMapper.updateTaskFailReason(tid, null);
                    }
                });
        try {
            while (true) {
                try {
                    int round = TaskSwitch.incrementExcelRound(accountId, inventoryType);
                    Long taskId = TaskSwitch.getExcelTaskId(accountId, inventoryType);
                    if (taskId != null) {
                        taskMapper.updateTaskRound(taskId, round);
                    }
                    log.info("[{}]{}压价任务开始执行第{}轮", account.getName(), inventoryType, round);

                    long startTime = System.currentTimeMillis();
                    if (fetchMode == ListingFetchMode.ALL) {
                        stockXService.priceDownWithExcelForAccount(account, inventoryType);
                    } else {
                        stockXService.priceDownWithExcelForAccount(account, inventoryType, fetchMode);
                    }
                    String cost = TimeUtil.getCostMin(startTime);
                    log.info("[{}]{}压价任务第{}轮执行完成，耗时:{}", account.getName(), inventoryType, round, cost);
                    if (taskId != null) {
                        taskMapper.updateTaskCost(taskId, cost);
                    }

                    if (detectCancel()) return;
                    if (sleepWithCancelCheck()) return;
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                    return;
                } catch (TaskCancelledException ce) {
                    detectCancel();
                    return;
                } catch (StockXRateLimitException rateLimitException) {
                    Long taskId = TaskSwitch.getExcelTaskId(accountId, inventoryType);
                    log.warn("[{}]{}压价任务因持续限流暂停: {}", account.getName(), inventoryType,
                            rateLimitException.getMessage());
                    if (taskId != null) {
                        taskMapper.updateTaskPaused(taskId, rateLimitException.getMessage());
                    }
                    return;
                } catch (Exception e) {
                    Long taskId = TaskSwitch.getExcelTaskId(accountId, inventoryType);
                    if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                        log.error("[{}]{}压价任务因Token过期终止，请更新Token后重新启动", account.getName(), inventoryType);
                        if (taskId != null) {
                            taskMapper.updateTaskFailed(taskId, "Token已过期，请更新Token");
                        }
                        return;
                    }
                    log.error("[{}]{}压价任务执行异常: {}", account.getName(), inventoryType, e.getMessage(), e);
                    if (taskId != null) {
                        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        if (reason.length() > 200) {
                            reason = reason.substring(0, 200);
                        }
                        taskMapper.updateTaskFailed(taskId, reason);
                    }
                    return;
                }
            }
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.setExcelRunning(accountId, inventoryType, false);
        }
    }

    private boolean sleepWithCancelCheck() throws InterruptedException {
        long remaining = TaskSwitch.getExcelInterval(account.getName(), inventoryType);
        while (remaining > 0) {
            if (detectCancel()) return true;
            Thread.sleep(Math.min(5000, remaining));
            remaining -= 5000;
        }
        return false;
    }

    private boolean detectCancel() {
        String accountId = account.getName();
        if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) {
            log.info("[{}]{}压价任务已取消，终止执行", account.getName(), inventoryType);
            Long taskId = TaskSwitch.getExcelTaskId(accountId, inventoryType);
            if (taskId != null) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            }
            TaskSwitch.clearExcelState(accountId, inventoryType);
            return true;
        }
        return false;
    }
}
