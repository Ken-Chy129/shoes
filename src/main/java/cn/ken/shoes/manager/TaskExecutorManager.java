package cn.ken.shoes.manager;

import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.task.KcTaskRunner;
import cn.ken.shoes.task.StockXPriceDownTaskRunner;
import cn.ken.shoes.task.StockXTaskRunner;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

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

    /**
     * 启动任务
     */
    public void startTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC -> {
                TaskSwitch.STOP_KC_TASK = false;
                if (!kcTaskRunner.isInit()) {
                    kcTaskRunner.start();
                }
            }
            case STOCKX_LISTING -> {
                TaskSwitch.STOP_STOCK_LISTING_TASK = false;
                if (!stockXTaskRunner.isInit()) {
                    stockXTaskRunner.start();
                }
            }
            case STOCKX_PRICE_DOWN -> {
                TaskSwitch.STOP_STOCK_PRICE_DOWN_TASK = false;
                if (!stockXPriceDownTaskRunner.isInit()) {
                    stockXPriceDownTaskRunner.start();
                }
            }
        }
    }

    /**
     * 停止任务
     */
    public void stopTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case KC -> TaskSwitch.STOP_KC_TASK = true;
            case STOCKX_LISTING -> TaskSwitch.STOP_STOCK_LISTING_TASK = true;
            case STOCKX_PRICE_DOWN -> TaskSwitch.STOP_STOCK_PRICE_DOWN_TASK = true;
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
}
