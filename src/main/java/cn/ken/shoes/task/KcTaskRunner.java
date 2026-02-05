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

/**
 * @author Ken-Chy129
 * @date 2025/5/19
 */
@Slf4j
@Component
public class KcTaskRunner extends Thread {

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
                // 增加轮次计数
                TaskSwitch.CURRENT_KC_ROUND++;
                Long taskId = TaskSwitch.CURRENT_KC_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskRound(taskId, TaskSwitch.CURRENT_KC_ROUND);
                }
                log.info("KC任务开始执行第{}轮", TaskSwitch.CURRENT_KC_ROUND);

                long startTime = System.currentTimeMillis();
                LockHelper.lockKcItem();
                try {
                    kickScrewService.refreshPriceV2();
                } finally {
                    LockHelper.unlockKcItem();
                }
                String cost = TimeUtil.getCostMin(startTime);
                log.info("KC任务第{}轮执行完成，耗时:{}", TaskSwitch.CURRENT_KC_ROUND, cost);
                if (taskId != null) {
                    taskMapper.updateTaskCost(taskId, cost);
                }

                if (detectCancelTask(taskId)) return;
                Thread.sleep(TaskSwitch.KC_TASK_INTERVAL);
                if (detectCancelTask(taskId)) return;
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            } catch (Exception e) {
                log.error("KC任务执行异常: {}", e.getMessage(), e);
                // 更新任务状态为失败
                Long taskId = TaskSwitch.CURRENT_KC_TASK_ID;
                if (taskId != null) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED.getCode());
                }
            }
        }
    }

    /**
     * 检查取消标志，取消后终止任务
     * @return true 表示任务已取消
     */
    private boolean detectCancelTask(Long taskId) {
        if (TaskSwitch.CANCEL_KC_TASK) {
            log.info("KC任务已取消，终止执行");
            if (taskId != null) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            }
            // 重置状态
            TaskSwitch.CANCEL_KC_TASK = false;
            TaskSwitch.CURRENT_KC_TASK_ID = null;
            TaskSwitch.CURRENT_KC_ROUND = 0;
            isInit = false;
            return true;
        }
        return false;
    }
}
