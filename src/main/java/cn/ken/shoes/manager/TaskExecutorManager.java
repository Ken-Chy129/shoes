package cn.ken.shoes.manager;

import com.alibaba.fastjson.JSONObject;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
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
    private StockXClient stockXClient;

    @Resource
    private KcTaskRunner kcTaskRunner;

    @Resource
    private KcPriceDownTaskRunner kcPriceDownTaskRunner;

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private TaskItemMapper taskItemMapper;

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
            default -> log.warn("startTask不支持的任务类型: {}", taskType);
        }
    }

    public void cancelTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case LISTING -> TaskSwitch.CANCEL_KC_LISTING_TASK = true;
            case PRICE_DOWN -> TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = true;
            default -> log.warn("cancelTask不支持的任务类型: {}", taskType);
        }
    }

    public boolean queryTaskStatus(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> kcTaskRunner.isInit() && !TaskSwitch.CANCEL_KC_LISTING_TASK;
            case PRICE_DOWN -> kcPriceDownTaskRunner.isInit() && !TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK;
            default -> false;
        };
    }

    public Long getCurrentTaskId(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> TaskSwitch.CURRENT_KC_LISTING_TASK_ID;
            case PRICE_DOWN -> TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID;
            default -> null;
        };
    }

    public int getCurrentRound(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> TaskSwitch.CURRENT_KC_LISTING_ROUND;
            case PRICE_DOWN -> TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND;
            default -> 0;
        };
    }

    public long getTaskInterval(TaskTypeEnum taskType) {
        return switch (taskType) {
            case LISTING -> TaskSwitch.KC_LISTING_TASK_INTERVAL;
            case PRICE_DOWN -> TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL;
            default -> 0;
        };
    }

    public void setTaskInterval(TaskTypeEnum taskType, long interval) {
        switch (taskType) {
            case LISTING -> TaskSwitch.KC_LISTING_TASK_INTERVAL = interval;
            case PRICE_DOWN -> TaskSwitch.KC_PRICE_DOWN_TASK_INTERVAL = interval;
            default -> log.warn("setTaskInterval不支持的任务类型: {}", taskType);
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

    public void startExcelPriceDown(String accountId, String inventoryType, boolean processOutsideExcel, String unprofitableAction) {
        if (TaskSwitch.isExcelRunning(accountId, inventoryType)) {
            log.info("Excel压价任务已在运行: {}:{}", accountId, inventoryType);
            return;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return;
        }
        String params = new JSONObject()
                .fluentPut("inventoryType", inventoryType)
                .fluentPut("processOutsideExcel", processOutsideExcel)
                .fluentPut("unprofitableAction", unprofitableAction)
                .toJSONString();
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
                                int pageCount, String searchType, int maxListCount, boolean modelNoSearch) {
        if (TaskSwitch.isSearchListRunning(accountId)) {
            log.info("搜索上架任务已在运行: {}", accountId);
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String taskTypeCode = modelNoSearch ? TaskTypeEnum.MODEL_SEARCH.getCode() : TaskTypeEnum.LISTING.getCode();
        String params = new com.alibaba.fastjson.JSONObject()
                .fluentPut("keywords", keywords)
                .fluentPut("sorts", sorts)
                .fluentPut("pageCount", pageCount)
                .fluentPut("searchType", searchType)
                .fluentPut("maxListCount", maxListCount)
                .fluentPut("modelNoSearch", modelNoSearch)
                .toJSONString();
        Long taskId = createTask("stockx", taskTypeCode, account.getName(), params);
        TaskSwitch.setSearchListTaskId(accountId, taskId);
        TaskSwitch.resetSearchListCancel(accountId);
        TaskSwitch.setSearchListRunning(accountId, true);

        StockXSearchListTaskRunner runner = new StockXSearchListTaskRunner(
                account, taskId, keywords, sorts, pageCount, searchType, maxListCount, modelNoSearch,
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

    // ==================== StockX 获取上架商品 ====================

    public Long startFetchListings(String accountId, String inventoryType) {
        String key = accountId + ":" + inventoryType;
        if (TaskSwitch.isFetchListingsRunning(key)) {
            log.info("获取上架商品任务已在运行: {}", key);
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String params = new JSONObject().fluentPut("inventoryType", inventoryType).toJSONString();
        Long taskId = createTask("stockx", TaskTypeEnum.FETCH_LISTINGS.getCode(), account.getName(), params);
        TaskSwitch.setFetchListingsTaskId(key, taskId);
        TaskSwitch.resetFetchListingsCancel(key);

        StockXFetchListingsTaskRunner runner = new StockXFetchListingsTaskRunner(
                account, taskId, inventoryType, stockXClient, taskMapper, taskItemMapper);
        new Thread(runner, "StockX-FetchListings-" + account.getName() + "-" + inventoryType).start();
        log.info("获取上架商品任务已启动: [{}] {}", account.getName(), inventoryType);
        return taskId;
    }

    public void cancelFetchListings(String accountId, String inventoryType) {
        TaskSwitch.cancelFetchListings(accountId + ":" + inventoryType);
    }

    public boolean isFetchListingsRunning(String accountId, String inventoryType) {
        return TaskSwitch.isFetchListingsRunning(accountId + ":" + inventoryType);
    }

    public Long getFetchListingsTaskId(String accountId, String inventoryType) {
        return TaskSwitch.getFetchListingsTaskId(accountId + ":" + inventoryType);
    }

    // ==================== StockX Excel下架 ====================

    public Long startExcelDelist(String accountId, String inventoryType) {
        String key = accountId + ":" + inventoryType;
        if (TaskSwitch.isExcelDelistRunning(key)) {
            log.info("Excel下架任务已在运行: {}", key);
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String params = new JSONObject().fluentPut("inventoryType", inventoryType).toJSONString();
        Long taskId = createTask("stockx", TaskTypeEnum.EXCEL_DELIST.getCode(), account.getName(), params);
        TaskSwitch.setExcelDelistTaskId(key, taskId);
        TaskSwitch.resetExcelDelistCancel(key);

        StockXExcelDelistTaskRunner runner = new StockXExcelDelistTaskRunner(
                account, taskId, inventoryType, stockXClient, taskMapper, taskItemMapper);
        new Thread(runner, "StockX-ExcelDelist-" + account.getName() + "-" + inventoryType).start();
        log.info("Excel下架任务已启动: [{}] {}", account.getName(), inventoryType);
        return taskId;
    }

    public void cancelExcelDelist(String accountId, String inventoryType) {
        TaskSwitch.cancelExcelDelist(accountId + ":" + inventoryType);
    }

    public boolean isExcelDelistRunning(String accountId, String inventoryType) {
        return TaskSwitch.isExcelDelistRunning(accountId + ":" + inventoryType);
    }

    public Long getExcelDelistTaskId(String accountId, String inventoryType) {
        return TaskSwitch.getExcelDelistTaskId(accountId + ":" + inventoryType);
    }
}
