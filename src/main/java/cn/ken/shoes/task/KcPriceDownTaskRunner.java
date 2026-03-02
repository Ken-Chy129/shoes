package cn.ken.shoes.task;

import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.LockHelper;
import cn.ken.shoes.util.TimeUtil;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KcPriceDownTaskRunner extends Thread {

    @Getter
    private boolean isInit = false;

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private TaskMapper taskMapper;

    @Override
    public void run() {
        isInit = true;
        while (true) {
            try {
                TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND++;
                Long taskId = TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskRound(taskId, TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND);
                }
                log.info("KC压价任务开始执行第{}轮", TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND);

                long startTime = System.currentTimeMillis();
                LockHelper.lockKcItem();
                try {
                    kickScrewService.priceDown();
                } finally {
                    LockHelper.unlockKcItem();
                }
                String cost = TimeUtil.getCostMin(startTime);
                log.info("KC压价任务第{}轮执行完成，耗时:{}", TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND, cost);
                if (taskId != null) {
                    taskMapper.updateTaskCost(taskId, cost);
                }

                if (detectCancelTask(taskId)) return;
                Thread.sleep(TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL);
                if (detectCancelTask(taskId)) return;
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            } catch (Exception e) {
                log.error("KC压价任务执行异常: {}", e.getMessage(), e);
                Long taskId = TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED.getCode());
                }
            }
        }
    }

    private boolean detectCancelTask(Long taskId) {
        if (TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK) {
            log.info("KC压价任务已取消，终止执行");
            if (taskId != null) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            }
            TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = false;
            TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID = null;
            TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND = 0;
            isInit = false;
            return true;
        }
        return false;
    }
}
