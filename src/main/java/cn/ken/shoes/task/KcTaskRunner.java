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
        Long taskId = TaskSwitch.CURRENT_KC_LISTING_TASK_ID;
        try {
            TaskSwitch.CURRENT_KC_LISTING_ROUND = 1;
            if (taskId != null) {
                taskMapper.updateTaskRound(taskId, TaskSwitch.CURRENT_KC_LISTING_ROUND);
            }
            log.info("KC上架任务开始执行");

            long startTime = System.currentTimeMillis();
            LockHelper.lockKcItem();
            try {
                kickScrewService.listing();
            } finally {
                LockHelper.unlockKcItem();
            }
            String cost = TimeUtil.getCostMin(startTime);

            // 检查是否被取消
            if (detectCancelTask(taskId, cost)) {
                return;
            }

            log.info("KC上架任务执行完成，耗时:{}", cost);
            if (taskId != null) {
                taskMapper.updateTaskCost(taskId, cost);
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            }
        } catch (Exception e) {
            log.error("KC上架任务执行异常: {}", e.getMessage(), e);
            if (taskId != null) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED.getCode());
            }
        } finally {
            // 任务完成后重置状态
            TaskSwitch.CURRENT_KC_LISTING_TASK_ID = null;
            TaskSwitch.CURRENT_KC_LISTING_ROUND = 0;
            isInit = false;
        }
    }

    /**
     * 检测任务是否被取消
     * @return true表示任务已取消，需要终止执行
     */
    private boolean detectCancelTask(Long taskId, String cost) {
        if (TaskSwitch.CANCEL_KC_LISTING_TASK) {
            log.info("KC上架任务已取消，终止执行");
            if (taskId != null) {
                if (cost != null) {
                    taskMapper.updateTaskCost(taskId, cost);
                }
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            }
            // 重置取消标志
            TaskSwitch.CANCEL_KC_LISTING_TASK = false;
            return true;
        }
        return false;
    }

}
