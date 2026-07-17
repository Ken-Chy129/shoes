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
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.service.StockXShippingExtensionService;
import cn.ken.shoes.task.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Resource
    private PriceManager priceManager;

    @Resource
    private StockXShippingExtensionService shippingExtensionService;

    @Resource
    private ConfigManager configManager;

    @Resource
    private TaskInputSnapshotStore taskInputSnapshotStore;

    /**
     * 启动任务（KC平台）
     */
    public synchronized Long startTask(TaskTypeEnum taskType) {
        switch (taskType) {
            case LISTING -> {
                if (kcTaskRunner.isInit()) {
                    return null;
                }
                TaskSwitch.CANCEL_KC_LISTING_TASK = false;
                Long kcListingTaskId = createTask("kickscrew", taskType.getCode());
                TaskSwitch.CURRENT_KC_LISTING_TASK_ID = kcListingTaskId;
                TaskSwitch.CURRENT_KC_LISTING_ROUND = 0;
                new Thread(kcTaskRunner, "KC-Listing-Task").start();
                return kcListingTaskId;
            }
            case PRICE_DOWN -> {
                if (kcPriceDownTaskRunner.isInit()) {
                    return null;
                }
                TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK = false;
                Long kcPdTaskId = createTask("kickscrew", taskType.getCode());
                TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID = kcPdTaskId;
                TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND = 0;
                new Thread(kcPriceDownTaskRunner, "KC-PriceDown-Task").start();
                return kcPdTaskId;
            }
            default -> {
                log.warn("startTask不支持的任务类型: {}", taskType);
                return null;
            }
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
            return switch (taskType) {
                case LISTING -> startTask(TaskTypeEnum.LISTING) != null;
                case PRICE_DOWN -> startTask(TaskTypeEnum.PRICE_DOWN) != null;
                default -> false;
            };
        }
        if ("stockx".equals(platform)) {
            if (StockXConfig.getAccount(account) == null) {
                return false;
            }
            return switch (taskType) {
                case PRICE_DOWN -> {
                    boolean hasExcel = params.getBooleanValue("hasExcel");
                    Map<String, ShoesContext.PriceDownConfig> input = Map.of();
                    if (hasExcel) {
                        var snapshot = taskInputSnapshotStore.loadPriceDown(task.getId());
                        if (snapshot.isEmpty()) {
                            log.error("重启恢复压价任务失败：历史Excel快照不存在, taskId:{}", task.getId());
                            yield false;
                        }
                        input = snapshot.get();
                    }
                    yield startExcelPriceDown(
                            account,
                            params.getString("inventoryType"),
                            hasExcel,
                            params.getBooleanValue("processOutsideExcel"),
                            params.getString("unprofitableAction"),
                            params.getLongValue("interval"),
                            input) != null;
                }
                case MODEL_SEARCH -> startSearchList(
                        account,
                        params.getString("keywords"),
                        params.getString("sorts"),
                        params.getIntValue("pageCount"),
                        params.getString("searchType"),
                        params.getIntValue("maxListCount"),
                        true) != null;
                case LISTING -> startSearchList(
                        account,
                        params.getString("keywords"),
                        params.getString("sorts"),
                        params.getIntValue("pageCount"),
                        params.getString("searchType"),
                        params.getIntValue("maxListCount"),
                        false) != null;
                default -> false;
            };
        }
        log.warn("重启恢复：不支持的平台: {}", platform);
        return false;
    }

    /**
     * 手动继续已暂停任务：复用原任务ID和已完成明细，不创建新任务行。
     */
    public Long resumePausedTask(TaskDO task) {
        if (task == null || !"stockx".equals(task.getPlatform())) {
            return null;
        }
        TaskTypeEnum taskType = TaskTypeEnum.fromCode(task.getTaskType());
        if (taskType == null) {
            return null;
        }
        JSONObject params = task.getParams() == null ? new JSONObject() : JSONObject.parseObject(task.getParams());
        return switch (taskType) {
            case PRICE_DOWN -> resumeExcelPriceDown(task, params);
            case LISTING, MODEL_SEARCH -> resumeSearchList(task, params, taskType == TaskTypeEnum.MODEL_SEARCH);
            case EXCEL_DELIST -> resumeExcelDelist(task, params);
            default -> null;
        };
    }

    /**
     * 按历史任务保存的平台、账号与参数创建一个全新的任务。
     */
    public boolean canRerun(TaskDO task) {
        if (task == null) {
            return false;
        }
        TaskTypeEnum taskType = TaskTypeEnum.fromCode(task.getTaskType());
        if (taskType == null) {
            return false;
        }
        if ("stockx".equals(task.getPlatform())) {
            return true;
        }
        return "kickscrew".equals(task.getPlatform())
                && (taskType == TaskTypeEnum.LISTING || taskType == TaskTypeEnum.PRICE_DOWN);
    }

    public Long rerunTask(TaskDO source) {
        if (!canRerun(source)) {
            return null;
        }
        TaskTypeEnum taskType = TaskTypeEnum.fromCode(source.getTaskType());
        if (taskType == null) {
            return null;
        }
        JSONObject params = source.getParams() == null ? new JSONObject() : JSONObject.parseObject(source.getParams());
        if ("kickscrew".equals(source.getPlatform())) {
            return startTask(taskType);
        }
        if (!"stockx".equals(source.getPlatform())) {
            return null;
        }
        String account = source.getAccountName();
        return switch (taskType) {
            case PRICE_DOWN -> {
                String inventoryType = inventoryType(params);
                boolean hasExcel = params.getBooleanValue("hasExcel");
                Map<String, ShoesContext.PriceDownConfig> input = Map.of();
                if (hasExcel) {
                    var snapshot = taskInputSnapshotStore.loadPriceDown(source.getId());
                    if (snapshot.isEmpty()) {
                        yield null;
                    }
                    input = snapshot.get();
                }
                yield startExcelPriceDown(
                        account,
                        inventoryType,
                        hasExcel,
                        params.getBooleanValue("processOutsideExcel"),
                        params.getString("unprofitableAction"),
                        params.getLongValue("interval"),
                        input);
            }
            case LISTING, MODEL_SEARCH -> startSearchList(
                    account,
                    params.getString("keywords"),
                    params.getString("sorts"),
                    params.getIntValue("pageCount"),
                    params.getString("searchType"),
                    params.getIntValue("maxListCount"),
                    taskType == TaskTypeEnum.MODEL_SEARCH);
            case FETCH_LISTINGS -> startFetchListings(account, inventoryType(params));
            case EXCEL_DELIST -> {
                String inventoryType = inventoryType(params);
                var snapshot = taskInputSnapshotStore.loadDelist(source.getId());
                yield snapshot.isEmpty() || snapshot.get().isEmpty()
                        ? null : startExcelDelist(account, inventoryType, snapshot.get());
            }
            case FETCH_ORDERS -> {
                List<StockXOrderCategory> categories = parseOrderCategories(params);
                yield categories.isEmpty() ? null : startFetchOrders(account, categories);
            }
            case EXTEND_SHIPPING -> shippingExtensionService.startManualAccount(account);
        };
    }

    private String inventoryType(JSONObject params) {
        return defaultIfBlank(params.getString("inventoryType"), "STANDARD");
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private List<StockXOrderCategory> parseOrderCategories(JSONObject params) {
        List<StockXOrderCategory> categories = new ArrayList<>();
        if (params.getJSONArray("orderTypes") == null) {
            return categories;
        }
        for (String code : params.getJSONArray("orderTypes").toJavaList(String.class)) {
            StockXOrderCategory.fromCode(code).ifPresent(categories::add);
        }
        return categories;
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

    public Long startExcelPriceDown(String accountId, String inventoryType, boolean hasExcel,
                                    boolean processOutsideExcel, String unprofitableAction,
                                    long intervalSeconds) {
        return startExcelPriceDown(accountId, inventoryType, hasExcel, processOutsideExcel,
                unprofitableAction, intervalSeconds, null);
    }

    private Long startExcelPriceDown(String accountId, String inventoryType, boolean hasExcel,
                                     boolean processOutsideExcel, String unprofitableAction,
                                     long intervalSeconds,
                                     Map<String, ShoesContext.PriceDownConfig> inputOverride) {
        inventoryType = defaultIfBlank(inventoryType, "STANDARD");
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String params = new JSONObject()
                .fluentPut("inventoryType", inventoryType)
                .fluentPut("hasExcel", hasExcel)
                .fluentPut("processOutsideExcel", processOutsideExcel)
                .fluentPut("unprofitableAction", unprofitableAction)
                .fluentPut("interval", intervalSeconds)
                .toJSONString();
        // 保险：标记含 Excel 但压价数据为空（多见于重启后持久化也丢失）→ 拒绝盲跑，避免击穿最低价贱卖
        Map<String, ShoesContext.PriceDownConfig> effectiveInput = hasExcel
                ? new LinkedHashMap<>(inputOverride != null
                        ? inputOverride
                        : ShoesContext.getPriceDownMap(accountId, inventoryType))
                : Map.of();
        if (hasExcel && effectiveInput.isEmpty()) {
            log.error("[{}]{}压价任务拒绝启动：标记含Excel但压价数据为空", accountId, inventoryType);
            return null;
        }
        if (!TaskSwitch.tryStartExcel(accountId, inventoryType)) {
            log.info("Excel压价任务已在运行: {}:{}", accountId, inventoryType);
            return null;
        }
        Long taskId = null;
        Map<String, ShoesContext.PriceDownConfig> previousInput =
                new LinkedHashMap<>(ShoesContext.getPriceDownMap(accountId, inventoryType));
        try {
            ShoesContext.getPriceDownMap(accountId, inventoryType).clear();
            if (hasExcel) {
                ShoesContext.getPriceDownMap(accountId, inventoryType).putAll(effectiveInput);
            } else {
                configManager.deletePriceDownExcel(accountId, inventoryType);
            }
            taskId = createTask("stockx", TaskTypeEnum.PRICE_DOWN.getCode(), account.getName(), params);
            taskInputSnapshotStore.savePriceDown(taskId,
                    new LinkedHashMap<>(ShoesContext.getPriceDownMap(accountId, inventoryType)));
            // 逐任务轮询间隔：>0 时按本次填写的值 seed 运行时缓存（不写账号配置）；<=0 时回退账号配置/默认值
            if (intervalSeconds > 0) {
                TaskSwitch.setExcelIntervalRuntime(accountId, inventoryType, intervalSeconds * 1000);
            }
            TaskSwitch.setExcelTaskId(accountId, inventoryType, taskId);
            TaskSwitch.resetExcelCancel(accountId, inventoryType);
            TaskSwitch.resetExcelRound(accountId, inventoryType);
            TaskSwitch.setProcessOutsideExcel(accountId, inventoryType, processOutsideExcel);
            TaskSwitch.setUnprofitableAction(accountId, inventoryType,
                    unprofitableAction != null ? unprofitableAction : "markup");

            StockXExcelPriceDownTaskRunner runner = new StockXExcelPriceDownTaskRunner(
                    account, inventoryType, stockXService, taskMapper);
            new Thread(runner, "StockX-Excel-" + account.getName() + "-" + inventoryType).start();
            log.info("Excel压价任务已启动: [{}] {}", account.getName(), inventoryType);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务输入保存或启动失败: " + e.getMessage());
            }
            ShoesContext.getPriceDownMap(accountId, inventoryType).clear();
            ShoesContext.getPriceDownMap(accountId, inventoryType).putAll(previousInput);
            TaskSwitch.clearExcelState(accountId, inventoryType);
            throw e;
        }
    }

    private Long resumeExcelPriceDown(TaskDO task, JSONObject params) {
        String accountId = task.getAccountName();
        String inventoryType = inventoryType(params);
        StockXAccount account = StockXConfig.getAccount(accountId);
        boolean hasExcel = params.getBooleanValue("hasExcel");
        if (account == null) {
            return null;
        }
        if (!TaskSwitch.tryStartExcel(accountId, inventoryType)) {
            return null;
        }
        Map<String, ShoesContext.PriceDownConfig> previousInput =
                new LinkedHashMap<>(ShoesContext.getPriceDownMap(accountId, inventoryType));
        boolean resumed = false;
        boolean started = false;
        try {
            if (hasExcel) {
                var snapshot = taskInputSnapshotStore.loadPriceDown(task.getId());
                if (snapshot.isEmpty() || snapshot.get().isEmpty()) {
                    return null;
                }
                Map<String, ShoesContext.PriceDownConfig> input = snapshot.get();
                ShoesContext.getPriceDownMap(accountId, inventoryType).clear();
                ShoesContext.getPriceDownMap(accountId, inventoryType).putAll(input);
            } else {
                ShoesContext.getPriceDownMap(accountId, inventoryType).clear();
                configManager.deletePriceDownExcel(accountId, inventoryType);
            }
            long intervalSeconds = params.getLongValue("interval");
            if (intervalSeconds > 0) {
                TaskSwitch.setExcelIntervalRuntime(accountId, inventoryType, intervalSeconds * 1000);
            }
            if (taskMapper.resumeTask(task.getId()) == 0) {
                return null;
            }
            resumed = true;
            TaskSwitch.setExcelTaskId(accountId, inventoryType, task.getId());
            TaskSwitch.setExcelRound(accountId, inventoryType, task.getRound() != null ? task.getRound() : 0);
            TaskSwitch.resetExcelCancel(accountId, inventoryType);
            TaskSwitch.setProcessOutsideExcel(accountId, inventoryType, params.getBooleanValue("processOutsideExcel"));
            TaskSwitch.setUnprofitableAction(accountId, inventoryType,
                    params.getString("unprofitableAction") != null ? params.getString("unprofitableAction") : "markup");
            new Thread(new StockXExcelPriceDownTaskRunner(account, inventoryType, stockXService, taskMapper),
                    "StockX-Excel-" + account.getName() + "-" + inventoryType).start();
            started = true;
            return task.getId();
        } catch (RuntimeException e) {
            if (resumed) {
                taskMapper.updateTaskPaused(task.getId(), "任务恢复启动失败: " + e.getMessage());
            }
            throw e;
        } finally {
            if (!started) {
                ShoesContext.getPriceDownMap(accountId, inventoryType).clear();
                ShoesContext.getPriceDownMap(accountId, inventoryType).putAll(previousInput);
                TaskSwitch.setExcelRunning(accountId, inventoryType, false);
            }
        }
    }

    // ==================== StockX 搜索上架 ====================

    public Long startSearchList(String accountId, String keywords, String sorts,
                                int pageCount, String searchType, int maxListCount,
                                boolean modelNoSearch) {
        sorts = defaultIfBlank(sorts, "featured");
        pageCount = pageCount > 0 ? pageCount : 3;
        searchType = defaultIfBlank(searchType, "shoes");
        maxListCount = Math.max(maxListCount, 0);
        if (keywords == null || keywords.isBlank()) {
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        if (!TaskSwitch.tryStartSearchList(accountId)) {
            log.info("搜索上架任务已在运行: {}", accountId);
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
        Long taskId = null;
        try {
            taskId = createTask("stockx", taskTypeCode, account.getName(), params);
            TaskSwitch.setSearchListTaskId(accountId, taskId);
            TaskSwitch.resetSearchListCancel(accountId);
            TaskSwitch.resetSearchVerification(taskId);

            StockXSearchListTaskRunner runner = new StockXSearchListTaskRunner(
                    account, taskId, keywords, sorts, pageCount, searchType, maxListCount, modelNoSearch,
                    stockXService, taskMapper);
            new Thread(runner, "StockX-SearchList-" + account.getName()).start();
            log.info("搜索上架任务已启动: [{}]", account.getName());
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务启动失败: " + e.getMessage());
            }
            TaskSwitch.clearSearchListRunState(accountId);
            throw e;
        }
    }

    private Long resumeSearchList(TaskDO task, JSONObject params, boolean modelNoSearch) {
        String accountId = task.getAccountName();
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null || params.getString("keywords") == null || params.getString("keywords").isBlank()) {
            return null;
        }
        if (!TaskSwitch.tryStartSearchList(accountId)) {
            return null;
        }
        if (taskMapper.resumeTask(task.getId()) == 0) {
            TaskSwitch.clearSearchListRunState(accountId);
            return null;
        }
        try {
            TaskSwitch.setSearchListTaskId(accountId, task.getId());
            TaskSwitch.resetSearchListCancel(accountId);
            TaskSwitch.resetSearchVerification(task.getId());
            StockXSearchListTaskRunner runner = new StockXSearchListTaskRunner(
                    account, task.getId(), params.getString("keywords"),
                    defaultIfBlank(params.getString("sorts"), "featured"),
                    params.getIntValue("pageCount") > 0 ? params.getIntValue("pageCount") : 3,
                    defaultIfBlank(params.getString("searchType"), "shoes"),
                    Math.max(params.getIntValue("maxListCount"), 0), modelNoSearch, stockXService, taskMapper);
            new Thread(runner, "StockX-SearchList-" + account.getName()).start();
            return task.getId();
        } catch (RuntimeException e) {
            taskMapper.updateTaskPaused(task.getId(), "任务恢复启动失败: " + e.getMessage());
            TaskSwitch.clearSearchListRunState(accountId);
            throw e;
        }
    }

    // ==================== StockX 获取上架商品 ====================

    public Long startFetchListings(String accountId, String inventoryType) {
        inventoryType = defaultIfBlank(inventoryType, "STANDARD");
        String key = accountId + ":" + inventoryType;
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        if (!TaskSwitch.tryStartFetchListings(key)) {
            log.info("获取上架商品任务已在运行: {}", key);
            return null;
        }
        String params = new JSONObject().fluentPut("inventoryType", inventoryType).toJSONString();
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.FETCH_LISTINGS.getCode(), account.getName(), params);
            TaskSwitch.setFetchListingsTaskId(key, taskId);
            TaskSwitch.resetFetchListingsCancel(key);

            StockXFetchListingsTaskRunner runner = new StockXFetchListingsTaskRunner(
                    account, taskId, inventoryType, stockXClient, taskMapper, taskItemMapper);
            new Thread(runner, "StockX-FetchListings-" + account.getName() + "-" + inventoryType).start();
            log.info("获取上架商品任务已启动: [{}] {}", account.getName(), inventoryType);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务启动失败: " + e.getMessage());
            }
            TaskSwitch.clearFetchListingsState(key);
            throw e;
        }
    }

    // ==================== StockX Excel下架 ====================

    public Long startExcelDelist(String accountId, String inventoryType) {
        return startExcelDelist(accountId, inventoryType,
                List.copyOf(ShoesContext.getDelistList(accountId, defaultIfBlank(inventoryType, "STANDARD"))));
    }

    private Long startExcelDelist(String accountId, String inventoryType, List<StockXDelistInputExcel> input) {
        inventoryType = defaultIfBlank(inventoryType, "STANDARD");
        String key = accountId + ":" + inventoryType;
        if (input == null || input.isEmpty()) {
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        if (!TaskSwitch.tryStartExcelDelist(key)) {
            log.info("Excel下架任务已在运行: {}", key);
            return null;
        }
        String params = new JSONObject().fluentPut("inventoryType", inventoryType).toJSONString();
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.EXCEL_DELIST.getCode(), account.getName(), params);
            List<StockXDelistInputExcel> snapshot = List.copyOf(input);
            taskInputSnapshotStore.saveDelist(taskId, snapshot);
            TaskSwitch.setExcelDelistTaskId(key, taskId);
            TaskSwitch.resetExcelDelistCancel(key);

            StockXExcelDelistTaskRunner runner = new StockXExcelDelistTaskRunner(
                    account, taskId, inventoryType, stockXClient, taskMapper, taskItemMapper, 0, snapshot);
            new Thread(runner, "StockX-ExcelDelist-" + account.getName() + "-" + inventoryType).start();
            log.info("Excel下架任务已启动: [{}] {}", account.getName(), inventoryType);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务输入保存或启动失败: " + e.getMessage());
            }
            TaskSwitch.clearExcelDelistState(key);
            throw e;
        }
    }

    private Long resumeExcelDelist(TaskDO task, JSONObject params) {
        String accountId = task.getAccountName();
        String inventoryType = inventoryType(params);
        String key = accountId + ":" + inventoryType;
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            return null;
        }
        var snapshot = taskInputSnapshotStore.loadDelist(task.getId());
        if (snapshot.isEmpty() || snapshot.get().isEmpty() || !TaskSwitch.tryStartExcelDelist(key)) {
            return null;
        }
        List<StockXDelistInputExcel> input = snapshot.get();
        if (taskMapper.resumeTask(task.getId()) == 0) {
            TaskSwitch.setExcelDelistRunning(key, false);
            return null;
        }
        try {
            TaskSwitch.setExcelDelistTaskId(key, task.getId());
            TaskSwitch.resetExcelDelistCancel(key);
            StockXExcelDelistTaskRunner runner = new StockXExcelDelistTaskRunner(
                    account, task.getId(), inventoryType, stockXClient, taskMapper, taskItemMapper,
                    task.getRound() != null ? task.getRound() : 0, input);
            new Thread(runner, "StockX-ExcelDelist-" + account.getName() + "-" + inventoryType).start();
            return task.getId();
        } catch (RuntimeException e) {
            taskMapper.updateTaskPaused(task.getId(), "任务恢复启动失败: " + e.getMessage());
            TaskSwitch.setExcelDelistRunning(key, false);
            throw e;
        }
    }

    // ==================== StockX 获取订单 ====================

    public Long startFetchOrders(String accountId, List<StockXOrderCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        if (!TaskSwitch.tryStartFetchOrders(accountId)) {
            log.info("获取订单任务已在运行: {}", accountId);
            return null;
        }
        String params = new JSONObject()
                .fluentPut("orderTypes", categories.stream().map(StockXOrderCategory::getCode).toList())
                .toJSONString();
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.FETCH_ORDERS.getCode(), account.getName(), params);
            TaskSwitch.resetFetchOrdersCancel(accountId);

            StockXFetchOrdersTaskRunner runner = new StockXFetchOrdersTaskRunner(
                    account, taskId, categories, stockXClient, priceManager, taskMapper, taskItemMapper);
            new Thread(runner, "StockX-FetchOrders-" + account.getName()).start();
            log.info("获取订单任务已启动: [{}], categories:{}", account.getName(), categories);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务启动失败: " + e.getMessage());
            }
            TaskSwitch.clearFetchOrdersState(accountId);
            throw e;
        }
    }

}
