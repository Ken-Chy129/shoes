package cn.ken.shoes.manager;

import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.task.KcTaskRunner;
import cn.ken.shoes.task.StockXPriceDownTaskRunner;
import cn.ken.shoes.task.StockXTaskRunner;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 任务执行管理器
 */
@Component
public class TaskExecutorManager {

    @Resource
    private KcTaskRunner kcTaskRunner;

    @Resource
    private StockXTaskRunner stockXTaskRunner;

    @Resource
    private StockXPriceDownTaskRunner stockXPriceDownTaskRunner;

    @Resource
    private TaskMapper taskMapper;

    /**
     * 启动任务
     */
    public void startTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC -> {
                TaskSwitch.STOP_KC_TASK = false;
                TaskSwitch.CANCEL_KC_TASK = false;
                if (!kcTaskRunner.isInit()) {
                    // 创建任务记录
                    Long taskId = createTask("kickscrew", taskType.getCode());
                    TaskSwitch.CURRENT_KC_TASK_ID = taskId;
                    TaskSwitch.CURRENT_KC_ROUND = 0;
                    kcTaskRunner.start();
                } else if (TaskSwitch.CURRENT_KC_TASK_ID == null) {
                    // 任务已运行但没有任务ID，创建新任务
                    Long taskId = createTask("kickscrew", taskType.getCode());
                    TaskSwitch.CURRENT_KC_TASK_ID = taskId;
                    TaskSwitch.CURRENT_KC_ROUND = 0;
                }
            }
            case STOCKX_LISTING -> {
                TaskSwitch.STOP_STOCK_LISTING_TASK = false;
                TaskSwitch.CANCEL_STOCK_LISTING_TASK = false;
                if (!stockXTaskRunner.isInit()) {
                    Long taskId = createTask("stockx", taskType.getCode());
                    TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID = taskId;
                    TaskSwitch.CURRENT_STOCK_LISTING_ROUND = 0;
                    stockXTaskRunner.start();
                } else if (TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID == null) {
                    Long taskId = createTask("stockx", taskType.getCode());
                    TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID = taskId;
                    TaskSwitch.CURRENT_STOCK_LISTING_ROUND = 0;
                }
            }
            case STOCKX_PRICE_DOWN -> {
                TaskSwitch.STOP_STOCK_PRICE_DOWN_TASK = false;
                TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK = false;
                if (!stockXPriceDownTaskRunner.isInit()) {
                    Long taskId = createTask("stockx", taskType.getCode());
                    TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID = taskId;
                    TaskSwitch.CURRENT_STOCK_PRICE_DOWN_ROUND = 0;
                    stockXPriceDownTaskRunner.start();
                } else if (TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID == null) {
                    Long taskId = createTask("stockx", taskType.getCode());
                    TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID = taskId;
                    TaskSwitch.CURRENT_STOCK_PRICE_DOWN_ROUND = 0;
                }
            }
        }
    }

    /**
     * 暂停任务（可恢复）
     */
    public void stopTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC -> {
                TaskSwitch.STOP_KC_TASK = true;
                updateTaskStatus(TaskSwitch.CURRENT_KC_TASK_ID, TaskDO.TaskStatusEnum.STOP.getCode());
            }
            case STOCKX_LISTING -> {
                TaskSwitch.STOP_STOCK_LISTING_TASK = true;
                updateTaskStatus(TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID, TaskDO.TaskStatusEnum.STOP.getCode());
            }
            case STOCKX_PRICE_DOWN -> {
                TaskSwitch.STOP_STOCK_PRICE_DOWN_TASK = true;
                updateTaskStatus(TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID, TaskDO.TaskStatusEnum.STOP.getCode());
            }
        }
    }

    /**
     * 取消任务（不可恢复，需要重新启动才能继续）
     * 只设置取消标志，TaskRunner 会在检测到标志后终止线程并更新状态
     */
    public void cancelTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC -> TaskSwitch.CANCEL_KC_TASK = true;
            case STOCKX_LISTING -> TaskSwitch.CANCEL_STOCK_LISTING_TASK = true;
            case STOCKX_PRICE_DOWN -> TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK = true;
        }
    }

    /**
     * 查询任务状态
     * @return true表示运行中，false表示已停止
     */
    public boolean queryTaskStatus(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC -> !TaskSwitch.STOP_KC_TASK;
            case STOCKX_LISTING -> !TaskSwitch.STOP_STOCK_LISTING_TASK;
            case STOCKX_PRICE_DOWN -> !TaskSwitch.STOP_STOCK_PRICE_DOWN_TASK;
        };
    }

    /**
     * 获取当前任务ID
     */
    public Long getCurrentTaskId(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC -> TaskSwitch.CURRENT_KC_TASK_ID;
            case STOCKX_LISTING -> TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID;
            case STOCKX_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID;
        };
    }

    /**
     * 获取当前轮次
     */
    public int getCurrentRound(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC -> TaskSwitch.CURRENT_KC_ROUND;
            case STOCKX_LISTING -> TaskSwitch.CURRENT_STOCK_LISTING_ROUND;
            case STOCKX_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_PRICE_DOWN_ROUND;
        };
    }

    /**
     * 获取任务间隔
     */
    public long getTaskInterval(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC -> TaskSwitch.KC_TASK_INTERVAL;
            case STOCKX_LISTING -> TaskSwitch.STOCK_LISTING_TASK_INTERVAL;
            case STOCKX_PRICE_DOWN -> TaskSwitch.STOCK_PRICE_DOWN_TASK_INTERVAL;
        };
    }

    /**
     * 设置任务间隔
     */
    public void setTaskInterval(TaskTypeEnum taskType, long interval) {
        switch (taskType) {
            case KC -> TaskSwitch.KC_TASK_INTERVAL = interval;
            case STOCKX_LISTING -> TaskSwitch.STOCK_LISTING_TASK_INTERVAL = interval;
            case STOCKX_PRICE_DOWN -> TaskSwitch.STOCK_PRICE_DOWN_TASK_INTERVAL = interval;
        }
    }

    /**
     * 创建任务记录
     */
    private Long createTask(String platform, String taskType) {
        TaskDO taskDO = new TaskDO();
        taskDO.setPlatform(platform);
        taskDO.setTaskType(taskType);
        taskDO.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        taskDO.setStartTime(new Date());
        taskDO.setRound(0);
        taskMapper.insert(taskDO);
        return taskDO.getId();
    }

    /**
     * 更新任务状态
     */
    private void updateTaskStatus(Long taskId, String status) {
        if (taskId != null) {
            taskMapper.updateTaskStatus(taskId, status);
        }
    }
}
