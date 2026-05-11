package cn.ken.shoes.task;

import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.util.TimeUtil;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StockXStandardPriceDownTaskRunner implements Runnable {

    @Getter
    private volatile boolean isInit = false;

    @Resource
    private StockXService stockXService;

    @Resource
    private TaskMapper taskMapper;

    @Override
    public void run() {
        isInit = true;
        while (true) {
            try {
                TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND++;
                Long taskId = TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskRound(taskId, TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND);
                }
                log.info("StockX现货压价任务开始执行第{}轮", TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND);

                long startTime = System.currentTimeMillis();
                stockXService.priceDownWithExcel("STANDARD");
                String cost = TimeUtil.getCostMin(startTime);
                log.info("StockX现货压价任务第{}轮执行完成，耗时:{}", TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND, cost);
                if (taskId != null) {
                    taskMapper.updateTaskCost(taskId, cost);
                }

                if (detectCancelTask(taskId)) return;
                if (sleepWithCancelCheck(taskId)) return;
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            } catch (Exception e) {
                log.error("StockX现货压价任务执行异常: {}", e.getMessage(), e);
                Long taskId = TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED.getCode());
                }
            }
        }
    }

    private boolean sleepWithCancelCheck(Long taskId) throws InterruptedException {
        long remaining = TaskSwitch.STOCK_STANDARD_PRICE_DOWN_TASK_INTERVAL;
        while (remaining > 0) {
            if (detectCancelTask(taskId)) return true;
            Thread.sleep(Math.min(5000, remaining));
            remaining -= 5000;
        }
        return false;
    }

    private boolean detectCancelTask(Long taskId) {
        if (TaskSwitch.CANCEL_STOCK_STANDARD_PRICE_DOWN_TASK) {
            log.info("StockX现货压价任务已取消，终止执行");
            if (taskId != null) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            }
            TaskSwitch.CANCEL_STOCK_STANDARD_PRICE_DOWN_TASK = false;
            TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID = null;
            TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND = 0;
            isInit = false;
            return true;
        }
        return false;
    }
}
