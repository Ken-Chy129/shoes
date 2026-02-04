package cn.ken.shoes.task;

import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.util.LockHelper;
import cn.ken.shoes.util.TimeUtil;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author Ken-Chy129
 * @date 2025/5/19
 */
@Slf4j
@Component
public class StockXTaskRunner extends Thread {

    @Getter
    private boolean isInit = false;

    @Resource
    private StockXService stockXService;

    @Resource
    private TaskMapper taskMapper;

    @Override
    public void run() {
        isInit = true;
        while (true) {
            try {
                // 增加轮次计数
                TaskSwitch.CURRENT_STOCK_LISTING_ROUND++;
                Long taskId = TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskRound(taskId, TaskSwitch.CURRENT_STOCK_LISTING_ROUND);
                }
                log.info("StockX上架任务开始执行第{}轮", TaskSwitch.CURRENT_STOCK_LISTING_ROUND);

                long startTime = System.currentTimeMillis();
                LockHelper.lockStockXItem();
                try {
                    stockXService.refreshPrices();
                } finally {
                    LockHelper.unlockStockXItem();
                }
                log.info("StockX上架任务第{}轮执行完成，耗时:{}", TaskSwitch.CURRENT_STOCK_LISTING_ROUND, TimeUtil.getCostMin(startTime));

                // 检查取消标志，取消后终止任务
                if (TaskSwitch.CANCEL_STOCK_LISTING_TASK) {
                    log.info("StockX上架任务已取消，终止执行");
                    if (taskId != null) {
                        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                    }
                    // 重置状态
                    TaskSwitch.CANCEL_STOCK_LISTING_TASK = false;
                    TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID = null;
                    TaskSwitch.CURRENT_STOCK_LISTING_ROUND = 0;
                    isInit = false;
                    // 终止线程
                    return;
                }

                Thread.sleep(TaskSwitch.STOCK_LISTING_TASK_INTERVAL);
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            } catch (Exception e) {
                log.error("StockX上架任务执行异常: {}", e.getMessage(), e);
                Long taskId = TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED.getCode());
                }
            }
        }
    }
}
