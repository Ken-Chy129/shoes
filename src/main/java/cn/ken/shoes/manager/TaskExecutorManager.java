package cn.ken.shoes.manager;

import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.task.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 任务执行管理器
 */
@Slf4j
@Component
public class TaskExecutorManager {

    @Resource
    private StockXService stockXService;

    @Resource
    private KcTaskRunner kcTaskRunner;

    @Resource
    private KcPriceDownTaskRunner kcPriceDownTaskRunner;

    @Resource
    private TaskMapper taskMapper;

    /**
     * 启动任务（KC平台）
     */
    public void startTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case LISTING -> {
                TaskSwitch.CANCEL_KC_LISTING_TASK = false;
                Long kcListingTaskId = createTask("kickscrew", taskType.getCode());
                TaskSwitch.CURRENT_KC_LISTING_TASK_ID = kcListingTaskId;
                TaskSwitch.CURRENT_KC_LISTING_ROUND = 0;
                if (!kcTaskRunner.isInit()) {
                    new Thread(kcTaskRunner, "KC-Listing-Task").start();
                }
            }
            case PRICE_DOWN -> {
                TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = false;
                Long kcPdTaskId = createTask("kickscrew", taskType.getCode());
                TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID = kcPdTaskId;
                TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND = 0;
                if (!kcPriceDownTaskRunner.isInit()) {
                    new Thread(kcPriceDownTaskRunner, "KC-PriceDown-Task").start();
                }
            }
        }
    }

    public void cancelTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case LISTING -> TaskSwitch.CANCEL_KC_LISTING_TASK = true;
            case PRICE_DOWN -> TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = true;
        }
    }

    public boolean queryTaskStatus(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> kcTaskRunner.isInit() && !TaskSwitch.CANCEL_KC_LISTING_TASK;
            case PRICE_DOWN -> kcPriceDownTaskRunner.isInit() && !TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK;
        };
    }

    public Long getCurrentTaskId(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> TaskSwitch.CURRENT_KC_LISTING_TASK_ID;
            case PRICE_DOWN -> TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID;
        };
    }

    public int getCurrentRound(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> TaskSwitch.CURRENT_KC_LISTING_ROUND;
            case PRICE_DOWN -> TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND;
        };
    }

    public long getTaskInterval(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> TaskSwitch.KC_LISTING_TASK_INTERVAL;
            case PRICE_DOWN -> TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL;
        };
    }

    public void setTaskInterval(TaskTypeEnum taskType, long interval) {
        switch (taskType) {
            case LISTING -> TaskSwitch.KC_LISTING_TASK_INTERVAL = interval;
            case PRICE_DOWN -> TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL = interval;
        }
    }

    /**
     * 创建任务记录
     */
    private Long createTask(String platform, String taskType) {
        return createTask(platform, taskType, null);
    }

    private Long createTask(String platform, String taskType, String accountName) {
        return createTask(platform, taskType, accountName, null);
    }

    private Long createTask(String platform, String taskType, String accountName, String params) {
        TaskDO taskDO = new TaskDO();
        taskDO.setPlatform(platform);
        taskDO.setTaskType(taskType);
        taskDO.setAccountName(accountName);
        taskDO.setParams(params);
        taskDO.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        taskDO.setStartTime(new Date());
        taskDO.setRound(0);
        taskMapper.insert(taskDO);

        return taskDO.getId();
    }


    // ==================== StockX Excel 多账号压价 ====================

    public void startExcelPriceDown(String accountId, String inventoryType) {
        if (TaskSwitch.isExcelRunning(accountId, inventoryType)) {
            log.info("Excel压价任务已在运行: {}:{}", accountId, inventoryType);
            return;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return;
        }
        String params = STR."""
                {"inventoryType":"\{inventoryType}"}""";
        Long taskId = createTask("stockx", TaskTypeEnum.PRICE_DOWN.getCode(), account.getName(), params);
        TaskSwitch.setExcelTaskId(accountId, inventoryType, taskId);
        TaskSwitch.resetExcelCancel(accountId, inventoryType);
        TaskSwitch.resetExcelRound(accountId, inventoryType);

        StockXExcelPriceDownTaskRunner runner = new StockXExcelPriceDownTaskRunner(
                account, inventoryType, stockXService, taskMapper);
        new Thread(runner, "StockX-Excel-" + account.getName() + "-" + inventoryType).start();
        log.info("Excel压价任务已启动: [{}] {}", account.getName(), inventoryType);
    }

    public void cancelExcelPriceDown(String accountId, String inventoryType) {
        TaskSwitch.cancelExcel(accountId, inventoryType);
        log.info("Excel压价任务已发送取消信号: {}:{}", accountId, inventoryType);
    }

    public boolean isExcelPriceDownRunning(String accountId, String inventoryType) {
        return TaskSwitch.isExcelRunning(accountId, inventoryType);
    }

    public Long getExcelPriceDownTaskId(String accountId, String inventoryType) {
        return TaskSwitch.getExcelTaskId(accountId, inventoryType);
    }

    public void setExcelPriceDownInterval(String accountId, String inventoryType, long interval) {
        TaskSwitch.setExcelInterval(accountId, inventoryType, interval);
    }

    public long getExcelPriceDownInterval(String accountId, String inventoryType) {
        return TaskSwitch.getExcelInterval(accountId, inventoryType);
    }

    // ==================== StockX 搜索上架 ====================

    public Long startSearchList(String accountId, String keywords, String sorts,
                                int pageCount, String searchType, int maxListCount) {
        if (TaskSwitch.isSearchListRunning(accountId)) {
            log.info("搜索上架任务已在运行: {}", accountId);
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String params = new com.alibaba.fastjson.JSONObject()
                .fluentPut("keywords", keywords)
                .fluentPut("sorts", sorts)
                .fluentPut("pageCount", pageCount)
                .fluentPut("searchType", searchType)
                .fluentPut("maxListCount", maxListCount)
                .toJSONString();
        Long taskId = createTask("stockx", TaskTypeEnum.LISTING.getCode(), account.getName(), params);
        TaskSwitch.setSearchListTaskId(accountId, taskId);
        TaskSwitch.resetSearchListCancel(accountId);
        TaskSwitch.setSearchListRunning(accountId, true);

        StockXSearchListTaskRunner runner = new StockXSearchListTaskRunner(
                account, taskId, keywords, sorts, pageCount, searchType, maxListCount,
                stockXService, taskMapper);
        new Thread(runner, "StockX-SearchList-" + account.getName()).start();
        log.info("搜索上架任务已启动: [{}]", account.getName());
        return taskId;
    }

    public void cancelSearchList(String accountId) {
        TaskSwitch.cancelSearchList(accountId);
    }

    public boolean isSearchListRunning(String accountId) {
        return TaskSwitch.isSearchListRunning(accountId);
    }

    public Long getSearchListTaskId(String accountId) {
        return TaskSwitch.getSearchListTaskId(accountId);
    }
}
