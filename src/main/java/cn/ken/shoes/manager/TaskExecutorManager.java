package cn.ken.shoes.manager;

import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.task.KcPriceDownTaskRunner;
import cn.ken.shoes.task.KcTaskRunner;
import cn.ken.shoes.task.StockXCustodialPriceDownTaskRunner;
import cn.ken.shoes.task.StockXPriceDownTaskRunner;
import cn.ken.shoes.task.StockXStandardPriceDownTaskRunner;
import cn.ken.shoes.task.StockXTaskRunner;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 任务执行管理器
 */
@Component
public class TaskExecutorManager {

    @Resource
    private KcTaskRunner kcTaskRunner;

    @Resource
    private KcPriceDownTaskRunner kcPriceDownTaskRunner;

    @Resource
    private StockXTaskRunner stockXTaskRunner;

    @Resource
    private StockXPriceDownTaskRunner stockXPriceDownTaskRunner;

    @Resource
    private StockXStandardPriceDownTaskRunner stockXStandardPriceDownTaskRunner;

    @Resource
    private StockXCustodialPriceDownTaskRunner stockXCustodialPriceDownTaskRunner;

    @Resource
    private TaskMapper taskMapper;

    /**
     * 启动任务
     */
    public void startTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC_LISTING -> {
                TaskSwitch.CANCEL_KC_LISTING_TASK = false;
                Long kcListingTaskId = createTask("kickscrew", taskType.getCode());
                TaskSwitch.CURRENT_KC_LISTING_TASK_ID = kcListingTaskId;
                TaskSwitch.CURRENT_KC_LISTING_ROUND = 0;
                if (!kcTaskRunner.isInit()) {
                    new Thread(kcTaskRunner, "KC-Listing-Task").start();
                }
            }
            case KC_PRICE_DOWN -> {
                TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = false;
                Long kcPdTaskId = createTask("kickscrew", taskType.getCode());
                TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID = kcPdTaskId;
                TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND = 0;
                if (!kcPriceDownTaskRunner.isInit()) {
                    new Thread(kcPriceDownTaskRunner, "KC-PriceDown-Task").start();
                }
            }
            case STOCKX_LISTING -> {
                TaskSwitch.CANCEL_STOCK_LISTING_TASK = false;
                Long sxListingTaskId = createTask("stockx", taskType.getCode());
                TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID = sxListingTaskId;
                TaskSwitch.CURRENT_STOCK_LISTING_ROUND = 0;
                if (!stockXTaskRunner.isInit()) {
                    new Thread(stockXTaskRunner, "StockX-Listing-Task").start();
                }
            }
            case STOCKX_PRICE_DOWN -> {
                TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK = false;
                Long sxPdTaskId = createTask("stockx", taskType.getCode());
                TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID = sxPdTaskId;
                TaskSwitch.CURRENT_STOCK_PRICE_DOWN_ROUND = 0;
                if (!stockXPriceDownTaskRunner.isInit()) {
                    new Thread(stockXPriceDownTaskRunner, "StockX-PriceDown-Task").start();
                }
            }
            case STOCKX_STANDARD_PRICE_DOWN -> {
                TaskSwitch.CANCEL_STOCK_STANDARD_PRICE_DOWN_TASK = false;
                Long sxStdPdTaskId = createTask("stockx", taskType.getCode());
                TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID = sxStdPdTaskId;
                TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND = 0;
                if (!stockXStandardPriceDownTaskRunner.isInit()) {
                    new Thread(stockXStandardPriceDownTaskRunner, "StockX-Standard-PriceDown-Task").start();
                }
            }
            case STOCKX_CUSTODIAL_PRICE_DOWN -> {
                TaskSwitch.CANCEL_STOCK_CUSTODIAL_PRICE_DOWN_TASK = false;
                Long sxCusPdTaskId = createTask("stockx", taskType.getCode());
                TaskSwitch.CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_TASK_ID = sxCusPdTaskId;
                TaskSwitch.CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_ROUND = 0;
                if (!stockXCustodialPriceDownTaskRunner.isInit()) {
                    new Thread(stockXCustodialPriceDownTaskRunner, "StockX-Custodial-PriceDown-Task").start();
                }
            }
        }
    }

    /**
     * 取消任务（终止任务执行）
     * 设置取消标志，TaskRunner 会在检测到标志后终止线程并更新状态
     */
    public void cancelTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC_LISTING -> TaskSwitch.CANCEL_KC_LISTING_TASK = true;
            case KC_PRICE_DOWN -> TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = true;
            case STOCKX_LISTING -> TaskSwitch.CANCEL_STOCK_LISTING_TASK = true;
            case STOCKX_PRICE_DOWN -> TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK = true;
            case STOCKX_STANDARD_PRICE_DOWN -> TaskSwitch.CANCEL_STOCK_STANDARD_PRICE_DOWN_TASK = true;
            case STOCKX_CUSTODIAL_PRICE_DOWN -> TaskSwitch.CANCEL_STOCK_CUSTODIAL_PRICE_DOWN_TASK = true;
        }
    }

    /**
     * 查询任务状态
     * @return true表示运行中，false表示已终止
     */
    public boolean queryTaskStatus(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC_LISTING -> kcTaskRunner.isInit() && !TaskSwitch.CANCEL_KC_LISTING_TASK;
            case KC_PRICE_DOWN -> kcPriceDownTaskRunner.isInit() && !TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK;
            case STOCKX_LISTING -> stockXTaskRunner.isInit() && !TaskSwitch.CANCEL_STOCK_LISTING_TASK;
            case STOCKX_PRICE_DOWN -> stockXPriceDownTaskRunner.isInit() && !TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK;
            case STOCKX_STANDARD_PRICE_DOWN -> stockXStandardPriceDownTaskRunner.isInit() && !TaskSwitch.CANCEL_STOCK_STANDARD_PRICE_DOWN_TASK;
            case STOCKX_CUSTODIAL_PRICE_DOWN -> stockXCustodialPriceDownTaskRunner.isInit() && !TaskSwitch.CANCEL_STOCK_CUSTODIAL_PRICE_DOWN_TASK;
        };
    }

    /**
     * 获取当前任务ID
     */
    public Long getCurrentTaskId(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC_LISTING -> TaskSwitch.CURRENT_KC_LISTING_TASK_ID;
            case KC_PRICE_DOWN -> TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID;
            case STOCKX_LISTING -> TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID;
            case STOCKX_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID;
            case STOCKX_STANDARD_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID;
            case STOCKX_CUSTODIAL_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_TASK_ID;
        };
    }

    /**
     * 获取当前轮次
     */
    public int getCurrentRound(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC_LISTING -> TaskSwitch.CURRENT_KC_LISTING_ROUND;
            case KC_PRICE_DOWN -> TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND;
            case STOCKX_LISTING -> TaskSwitch.CURRENT_STOCK_LISTING_ROUND;
            case STOCKX_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_PRICE_DOWN_ROUND;
            case STOCKX_STANDARD_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND;
            case STOCKX_CUSTODIAL_PRICE_DOWN -> TaskSwitch.CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_ROUND;
        };
    }

    /**
     * 获取任务间隔
     */
    public long getTaskInterval(TaskTypeEnum taskType) {
        return switch (taskType) {
            case KC_LISTING -> TaskSwitch.KC_LISTING_TASK_INTERVAL;
            case KC_PRICE_DOWN -> TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL;
            case STOCKX_LISTING -> TaskSwitch.STOCK_LISTING_TASK_INTERVAL;
            case STOCKX_PRICE_DOWN -> TaskSwitch.STOCK_PRICE_DOWN_TASK_INTERVAL;
            case STOCKX_STANDARD_PRICE_DOWN -> TaskSwitch.STOCK_STANDARD_PRICE_DOWN_TASK_INTERVAL;
            case STOCKX_CUSTODIAL_PRICE_DOWN -> TaskSwitch.STOCK_CUSTODIAL_PRICE_DOWN_TASK_INTERVAL;
        };
    }

    /**
     * 设置任务间隔
     */
    public void setTaskInterval(TaskTypeEnum taskType, long interval) {
        switch (taskType) {
            case KC_LISTING -> TaskSwitch.KC_LISTING_TASK_INTERVAL = interval;
            case KC_PRICE_DOWN -> TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL = interval;
            case STOCKX_LISTING -> TaskSwitch.STOCK_LISTING_TASK_INTERVAL = interval;
            case STOCKX_PRICE_DOWN -> TaskSwitch.STOCK_PRICE_DOWN_TASK_INTERVAL = interval;
            case STOCKX_STANDARD_PRICE_DOWN -> TaskSwitch.STOCK_STANDARD_PRICE_DOWN_TASK_INTERVAL = interval;
            case STOCKX_CUSTODIAL_PRICE_DOWN -> TaskSwitch.STOCK_CUSTODIAL_PRICE_DOWN_TASK_INTERVAL = interval;
        }
    }

    /**
     * 创建任务记录
     */
    private Long createTask(String platform, String taskType) {
        // 先搁置历史遗留的运行中任务（不在当前内存有效ID列表中的）
        List<Long> validTaskIds = collectValidTaskIds();
        taskMapper.shelveHistoryTasks(validTaskIds);

        // 再创建新任务
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
     * 收集当前内存中所有有效的任务ID
     */
    private List<Long> collectValidTaskIds() {
        List<Long> validIds = new ArrayList<>();
        if (TaskSwitch.CURRENT_KC_LISTING_TASK_ID != null) {
            validIds.add(TaskSwitch.CURRENT_KC_LISTING_TASK_ID);
        }
        if (TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID != null) {
            validIds.add(TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID);
        }
        if (TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID != null) {
            validIds.add(TaskSwitch.CURRENT_STOCK_LISTING_TASK_ID);
        }
        if (TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID != null) {
            validIds.add(TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID);
        }
        if (TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID != null) {
            validIds.add(TaskSwitch.CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID);
        }
        if (TaskSwitch.CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_TASK_ID != null) {
            validIds.add(TaskSwitch.CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_TASK_ID);
        }
        return validIds;
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
