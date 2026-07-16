package cn.ken.shoes.manager;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.common.StockXOrderCategory;
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

    /**
     * 服务重启后自动恢复运行中的任务。
     * 崩溃/重启会丢失 JVM 内的任务线程与 TaskSwitch 状态，但 DB task 表里 status=running 的行完整保留了恢复所需入参。
     * 流程：快照 running 行 -> 全部置为 shelved -> 对 resumeOnStartup=true 的类型按 (platform, code) 重新拉起（新建任务行重跑）。
     * 注意：必须在 StockX 账号配置加载完成后调用（依赖 StockXConfig.getAccount）。
     */
    public void resumeRunningTasks() {
        List<TaskDO> runningTasks = taskMapper.selectList(new QueryWrapper<TaskDO>().eq("status", "running"));
        // 先把旧的 running 行统一置为 shelved（此时尚未重建新行，不受影响）
        taskMapper.shelveHistoryTasks(List.of());
        if (runningTasks == null || runningTasks.isEmpty()) {
            return;
        }
        int resumed = 0;
        for (TaskDO task : runningTasks) {
            TaskTypeEnum taskType = TaskTypeEnum.fromCode(task.getTaskType());
            if (taskType == null || !taskType.isResumeOnStartup()) {
                continue;
            }
            try {
                if (resumeTask(task, taskType)) {
                    resumed++;
                    log.info("重启恢复任务成功: platform={}, type={}, account={}, oldTaskId={}",
                            task.getPlatform(), taskType.getDesc(), task.getAccountName(), task.getId());
                }
            } catch (Exception e) {
                log.error("重启恢复任务失败: platform={}, type={}, account={}, oldTaskId={}, params={}",
                        task.getPlatform(), taskType.getDesc(), task.getAccountName(), task.getId(), task.getParams(), e);
            }
        }
        log.info("重启任务恢复完成，共恢复 {} 个任务（扫描 {} 个运行中任务）", resumed, runningTasks.size());
    }

    /**
     * 按 (platform, taskType) 分派到对应的 start* 方法重新拉起。返回是否成功分派。
     */
    private boolean resumeTask(TaskDO task, TaskTypeEnum taskType) {
        String platform = task.getPlatform();
        String account = task.getAccountName();
        JSONObject params = task.getParams() == null ? new JSONObject() : JSONObject.parseObject(task.getParams());
        if ("kickscrew".equals(platform)) {
            switch (taskType) {
                case LISTING -> startTask(TaskTypeEnum.LISTING);
                case PRICE_DOWN -> startTask(TaskTypeEnum.PRICE_DOWN);
                default -> {
                    log.warn("重启恢复：kickscrew 不支持的任务类型: {}", taskType);
                    return false;
                }
            }
            return true;
        }
        if ("stockx".equals(platform)) {
            switch (taskType) {
                case PRICE_DOWN -> startExcelPriceDown(
                        account,
                        params.getString("inventoryType"),
                        params.getBooleanValue("hasExcel"),
                        params.getBooleanValue("processOutsideExcel"),
                        params.getString("unprofitableAction"),
                        params.getLongValue("interval"));
                case MODEL_SEARCH -> startSearchList(
                        account,
                        params.getString("keywords"),
                        params.getString("sorts"),
                        params.getIntValue("pageCount"),
                        params.getString("searchType"),
                        params.getIntValue("maxListCount"),
                        true);
                case LISTING -> startSearchList(
                        account,
                        params.getString("keywords"),
                        params.getString("sorts"),
                        params.getIntValue("pageCount"),
                        params.getString("searchType"),
                        params.getIntValue("maxListCount"),
                        false);
                default -> {
                    log.warn("重启恢复：stockx 不支持的任务类型: {}", taskType);
                    return false;
                }
            }
            return true;
        }
        log.warn("重启恢复：不支持的平台: {}", platform);
        return false;
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

    public void startExcelPriceDown(String accountId, String inventoryType, boolean hasExcel, boolean processOutsideExcel, String unprofitableAction, long intervalSeconds) {
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
                .fluentPut("hasExcel", hasExcel)
                .fluentPut("processOutsideExcel", processOutsideExcel)
                .fluentPut("unprofitableAction", unprofitableAction)
                .fluentPut("interval", intervalSeconds)
                .toJSONString();
        // 保险：标记含 Excel 但压价数据为空（多见于重启后持久化也丢失）→ 拒绝盲跑，避免击穿最低价贱卖
        if (hasExcel && ShoesContext.getPriceDownMap(accountId, inventoryType).isEmpty()) {
            Long failedTaskId = createTask("stockx", TaskTypeEnum.PRICE_DOWN.getCode(), account.getName(), params);
            taskMapper.updateTaskFailed(failedTaskId, "重启后压价Excel数据丢失，请重新上传Excel并重新启动任务");
            log.error("[{}]{}压价任务拒绝启动：标记含Excel但压价数据为空", accountId, inventoryType);
            return;
        }
        Long taskId = createTask("stockx", TaskTypeEnum.PRICE_DOWN.getCode(), account.getName(), params);
        // 逐任务轮询间隔：>0 时按本次填写的值 seed 运行时缓存（不写账号配置）；<=0 时回退账号配置/默认值
        if (intervalSeconds > 0) {
            TaskSwitch.setExcelIntervalRuntime(accountId, inventoryType, intervalSeconds * 1000);
        }
        TaskSwitch.setExcelTaskId(accountId, inventoryType, taskId);
        TaskSwitch.resetExcelCancel(accountId, inventoryType);
        TaskSwitch.resetExcelRound(accountId, inventoryType);

        StockXExcelPriceDownTaskRunner runner = new StockXExcelPriceDownTaskRunner(
                account, inventoryType, stockXService, taskMapper);
        new Thread(runner, "StockX-Excel-" + account.getName() + "-" + inventoryType).start();
        log.info("Excel压价任务已启动: [{}] {}", account.getName(), inventoryType);
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

    // ==================== StockX 获取订单 ====================

    public Long startFetchOrders(String accountId, List<StockXOrderCategory> categories, boolean fetchPayout) {
        if (TaskSwitch.isFetchOrdersRunning(accountId)) {
            log.info("获取订单任务已在运行: {}", accountId);
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String params = new JSONObject()
                .fluentPut("orderTypes", categories.stream().map(StockXOrderCategory::getCode).toList())
                .fluentPut("fetchPayout", fetchPayout)
                .toJSONString();
        Long taskId = createTask("stockx", TaskTypeEnum.FETCH_ORDERS.getCode(), account.getName(), params);
        TaskSwitch.resetFetchOrdersCancel(accountId);

        StockXFetchOrdersTaskRunner runner = new StockXFetchOrdersTaskRunner(
                account, taskId, categories, fetchPayout, stockXClient, taskMapper, taskItemMapper);
        new Thread(runner, "StockX-FetchOrders-" + account.getName()).start();
        log.info("获取订单任务已启动: [{}], categories:{}, fetchPayout:{}", account.getName(), categories, fetchPayout);
        return taskId;
    }

}
