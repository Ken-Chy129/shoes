package cn.ken.shoes.manager;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.common.DelistMode;
import cn.ken.shoes.common.ModelSearchOperation;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import cn.ken.shoes.model.search.ModelNoSearchSizeFilter;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.service.StockXReplenishmentService;
import cn.ken.shoes.service.StockXShippingExtensionService;
import cn.ken.shoes.task.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private StockXReplenishmentService replenishmentService;

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
                            listingFetchMode(params),
                            input) != null;
                }
                case MODEL_SEARCH -> {
                    ModelSearchOperation operation = ModelSearchOperation.fromCode(params.getString("operation"));
                    if (operation == null) {
                        yield startSearchList(
                                account,
                                params.getString("keywords"),
                                params.getString("sorts"),
                                params.getIntValue("pageCount"),
                                params.getString("searchType"),
                                params.getIntValue("maxListCount"),
                                true,
                                readModelNoSizeFilters(params)) != null;
                    }
                    yield startModelSearchFromSnapshot(account, operation, task.getId()) != null;
                }
                case LISTING -> {
                    if (isModelNoSearch(params) && params.getString("keywords") == null) {
                        var snapshot = taskInputSnapshotStore.loadSearchModelNoInput(task.getId());
                        if (snapshot.isEmpty()) {
                            log.error("重启恢复货号搜索上架失败：历史货号快照不存在, taskId:{}", task.getId());
                            yield false;
                        }
                        yield startModelNoSearchList(account, snapshot.get(),
                                params.getString("searchType"), params.getIntValue("maxListCount")) != null;
                    }
                    yield startSearchList(
                            account,
                            params.getString("keywords"),
                            params.getString("sorts"),
                            params.getIntValue("pageCount"),
                            params.getString("searchType"),
                            params.getIntValue("maxListCount"),
                            isModelNoSearch(params)) != null;
                }
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
            case LISTING -> resumeSearchList(task, params, isModelNoSearch(params));
            case MODEL_SEARCH -> ModelSearchOperation.fromCode(params.getString("operation")) != null
                    ? resumeModelSearch(task, params)
                    : resumeSearchList(task, params, true);
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
                        listingFetchMode(params),
                        input);
            }
            case LISTING -> {
                if (isModelNoSearch(params) && params.getString("keywords") == null) {
                    var snapshot = taskInputSnapshotStore.loadSearchModelNoInput(source.getId());
                    yield snapshot.isEmpty() ? null : startModelNoSearchList(account, snapshot.get(),
                            params.getString("searchType"), params.getIntValue("maxListCount"));
                }
                yield startSearchList(
                        account,
                        params.getString("keywords"),
                        params.getString("sorts"),
                        params.getIntValue("pageCount"),
                        params.getString("searchType"),
                        params.getIntValue("maxListCount"),
                        isModelNoSearch(params));
            }
            case MODEL_SEARCH -> {
                ModelSearchOperation operation = ModelSearchOperation.fromCode(params.getString("operation"));
                yield operation != null
                        ? startModelSearchFromSnapshot(account, operation, source.getId())
                        : startSearchList(
                                account,
                                params.getString("keywords"),
                                params.getString("sorts"),
                                params.getIntValue("pageCount"),
                                params.getString("searchType"),
                                params.getIntValue("maxListCount"),
                                true,
                                readModelNoSizeFilters(params));
            }
            case FETCH_LISTINGS -> startFetchListings(account, inventoryType(params));
            case EXCEL_DELIST -> {
                String inventoryType = inventoryType(params);
                DelistMode mode = delistMode(params);
                if (mode == DelistMode.ALL) {
                    yield startDelist(account, inventoryType, mode);
                }
                var snapshot = taskInputSnapshotStore.loadDelist(source.getId());
                yield snapshot.isEmpty() || snapshot.get().isEmpty()
                        ? null : startDelist(account, inventoryType, mode, snapshot.get());
            }
            case FETCH_ORDERS -> {
                List<StockXOrderCategory> categories = parseOrderCategories(params);
                yield categories.isEmpty() ? null : startFetchOrders(account, categories);
            }
            case EXTEND_SHIPPING -> shippingExtensionService.startManualAccount(account);
            case REPLENISHMENT -> startReplenishmentFromParams(account, params);
        };
    }

    private Long startReplenishmentFromParams(String account, JSONObject params) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        Instant startTime = LocalDateTime.parse(params.getString("soldStartTime"), formatter)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant endTime = LocalDateTime.parse(params.getString("soldEndTime"), formatter)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        return replenishmentService.startManualAccount(account, startTime, endTime);
    }

    private String inventoryType(JSONObject params) {
        return defaultIfBlank(params.getString("inventoryType"), "STANDARD");
    }

    private ListingFetchMode listingFetchMode(JSONObject params) {
        return ListingFetchMode.fromCode(params.getString("listingFetchMode"));
    }

    private DelistMode delistMode(JSONObject params) {
        DelistMode mode = DelistMode.fromCode(params.getString("delistMode"));
        return mode != null ? mode : DelistMode.EXCEL;
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
                unprofitableAction, intervalSeconds, ListingFetchMode.ALL);
    }

    public Long startExcelPriceDown(String accountId, String inventoryType, boolean hasExcel,
                                    boolean processOutsideExcel, String unprofitableAction,
                                    long intervalSeconds, ListingFetchMode fetchMode) {
        return startExcelPriceDown(accountId, inventoryType, hasExcel, processOutsideExcel,
                unprofitableAction, intervalSeconds, fetchMode, null);
    }

    private Long startExcelPriceDown(String accountId, String inventoryType, boolean hasExcel,
                                     boolean processOutsideExcel, String unprofitableAction,
                                     long intervalSeconds,
                                     ListingFetchMode fetchMode,
                                     Map<String, ShoesContext.PriceDownConfig> inputOverride) {
        inventoryType = defaultIfBlank(inventoryType, "STANDARD");
        fetchMode = fetchMode != null ? fetchMode : ListingFetchMode.ALL;
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        if (fetchMode == ListingFetchMode.EXCEL_SEARCH && !hasExcel) {
            log.error("[{}]{}压价任务拒绝启动：按Excel货号搜索模式未上传Excel", accountId, inventoryType);
            return null;
        }
        String params = new JSONObject()
                .fluentPut("inventoryType", inventoryType)
                .fluentPut("hasExcel", hasExcel)
                .fluentPut("processOutsideExcel", processOutsideExcel)
                .fluentPut("listingFetchMode", fetchMode.getCode())
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
        if (!TaskSwitch.tryStartExcel(accountId, inventoryType, fetchMode)) {
            log.info("压价任务通道已在运行: {}:{}:{}", accountId, inventoryType, fetchMode.getCode());
            return null;
        }
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.PRICE_DOWN.getCode(), account.getName(), params);
            taskInputSnapshotStore.savePriceDown(taskId, effectiveInput);
            // 逐任务轮询间隔：>0 时按本次填写的值 seed 运行时缓存（不写账号配置）；<=0 时回退账号配置/默认值
            if (intervalSeconds > 0) {
                TaskSwitch.setExcelIntervalRuntime(accountId, inventoryType, fetchMode, intervalSeconds * 1000);
            }
            TaskSwitch.setExcelTaskId(accountId, inventoryType, fetchMode, taskId);
            TaskSwitch.resetExcelCancel(accountId, inventoryType, fetchMode);
            TaskSwitch.resetExcelRound(accountId, inventoryType, fetchMode);
            TaskSwitch.setProcessOutsideExcel(accountId, inventoryType, fetchMode, processOutsideExcel);
            TaskSwitch.setUnprofitableAction(accountId, inventoryType, fetchMode,
                    unprofitableAction != null ? unprofitableAction : "markup");
            TaskSwitch.setPriceDownInput(accountId, inventoryType, fetchMode, effectiveInput);

            StockXExcelPriceDownTaskRunner runner = new StockXExcelPriceDownTaskRunner(
                    account, inventoryType, fetchMode, stockXService, taskMapper);
            new Thread(runner, "StockX-PriceDown-" + account.getName() + "-" + inventoryType
                    + "-" + fetchMode.getCode()).start();
            log.info("Excel压价任务已启动: [{}] {}", account.getName(), inventoryType);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务输入保存或启动失败: " + e.getMessage());
            }
            TaskSwitch.clearExcelState(accountId, inventoryType, fetchMode);
            throw e;
        }
    }

    private Long resumeExcelPriceDown(TaskDO task, JSONObject params) {
        String accountId = task.getAccountName();
        String inventoryType = inventoryType(params);
        StockXAccount account = StockXConfig.getAccount(accountId);
        boolean hasExcel = params.getBooleanValue("hasExcel");
        ListingFetchMode fetchMode = listingFetchMode(params);
        if (account == null) {
            return null;
        }
        if (fetchMode == ListingFetchMode.EXCEL_SEARCH && !hasExcel) {
            return null;
        }
        if (!TaskSwitch.tryStartExcel(accountId, inventoryType, fetchMode)) {
            return null;
        }
        boolean resumed = false;
        boolean started = false;
        try {
            Map<String, ShoesContext.PriceDownConfig> input = Map.of();
            if (hasExcel) {
                var snapshot = taskInputSnapshotStore.loadPriceDown(task.getId());
                if (snapshot.isEmpty() || snapshot.get().isEmpty()) {
                    return null;
                }
                input = snapshot.get();
            }
            long intervalSeconds = params.getLongValue("interval");
            if (intervalSeconds > 0) {
                TaskSwitch.setExcelIntervalRuntime(accountId, inventoryType, fetchMode, intervalSeconds * 1000);
            }
            if (taskMapper.resumeTask(task.getId()) == 0) {
                return null;
            }
            resumed = true;
            TaskSwitch.setExcelTaskId(accountId, inventoryType, fetchMode, task.getId());
            TaskSwitch.setExcelRound(accountId, inventoryType, fetchMode,
                    task.getRound() != null ? task.getRound() : 0);
            TaskSwitch.resetExcelCancel(accountId, inventoryType, fetchMode);
            TaskSwitch.setProcessOutsideExcel(accountId, inventoryType, fetchMode,
                    params.getBooleanValue("processOutsideExcel"));
            TaskSwitch.setUnprofitableAction(accountId, inventoryType, fetchMode,
                    params.getString("unprofitableAction") != null ? params.getString("unprofitableAction") : "markup");
            TaskSwitch.setPriceDownInput(accountId, inventoryType, fetchMode, input);
            new Thread(new StockXExcelPriceDownTaskRunner(account, inventoryType, fetchMode, stockXService, taskMapper),
                    "StockX-PriceDown-" + account.getName() + "-" + inventoryType
                            + "-" + fetchMode.getCode()).start();
            started = true;
            return task.getId();
        } catch (RuntimeException e) {
            if (resumed) {
                taskMapper.updateTaskPaused(task.getId(), "任务恢复启动失败: " + e.getMessage());
            }
            throw e;
        } finally {
            if (!started) {
                TaskSwitch.clearExcelState(accountId, inventoryType, fetchMode);
            }
        }
    }

    // ==================== StockX 搜索上架 ====================

    public Long startModelSearchPriceFetch(String accountId, List<ModelNoSearchExcel> rows) {
        return startModelSearch(accountId, ModelSearchOperation.FETCH_PRICE, rows, List.of());
    }

    public Long startModelSearchListing(String accountId, List<ModelSearchListingExcel> rows) {
        return startModelSearch(accountId, ModelSearchOperation.CREATE_LISTING, List.of(), rows);
    }

    private Long startModelSearch(String accountId, ModelSearchOperation operation,
                                  List<ModelNoSearchExcel> priceRows,
                                  List<ModelSearchListingExcel> listingRows) {
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        JSONObject params = new JSONObject(true)
                .fluentPut("operation", operation.getCode())
                .fluentPut("inputCount", operation == ModelSearchOperation.FETCH_PRICE
                        ? priceRows.size() : listingRows.size());
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.MODEL_SEARCH.getCode(), account.getName(), params.toJSONString());
            if (operation == ModelSearchOperation.FETCH_PRICE) {
                taskInputSnapshotStore.saveModelSearchPriceInput(taskId, priceRows);
            } else {
                taskInputSnapshotStore.saveModelSearchListingInput(taskId, listingRows);
            }
            startModelSearchRunner(account, taskId, operation, priceRows, listingRows);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务启动失败: " + e.getMessage());
                TaskSwitch.clearSearchListRunState(taskId);
            }
            throw e;
        }
    }

    private void startModelSearchRunner(StockXAccount account, Long taskId, ModelSearchOperation operation,
                                        List<ModelNoSearchExcel> priceRows,
                                        List<ModelSearchListingExcel> listingRows) {
        TaskSwitch.markSearchListRunning(taskId);
        TaskSwitch.resetSearchListCancel(taskId);
        TaskSwitch.resetSearchVerification(taskId);
        StockXModelSearchTaskRunner runner = new StockXModelSearchTaskRunner(
                account, taskId, operation, priceRows, listingRows, stockXService, taskMapper);
        new Thread(runner, "StockX-ModelSearch-" + operation.getCode() + "-" + account.getName()).start();
    }

    private Long startModelSearchFromSnapshot(String accountId, ModelSearchOperation operation, Long sourceTaskId) {
        if (operation == ModelSearchOperation.FETCH_PRICE) {
            var input = taskInputSnapshotStore.loadModelSearchPriceInput(sourceTaskId);
            return input.isPresent() && !input.get().isEmpty()
                    ? startModelSearchPriceFetch(accountId, input.get()) : null;
        }
        var input = taskInputSnapshotStore.loadModelSearchListingInput(sourceTaskId);
        return input.isPresent() && !input.get().isEmpty()
                ? startModelSearchListing(accountId, input.get()) : null;
    }

    private Long resumeModelSearch(TaskDO task, JSONObject params) {
        ModelSearchOperation operation = ModelSearchOperation.fromCode(params.getString("operation"));
        StockXAccount account = StockXConfig.getAccount(task.getAccountName());
        if (operation == null || account == null || TaskSwitch.isSearchListRunning(task.getId())) {
            return null;
        }
        List<ModelNoSearchExcel> priceRows = List.of();
        List<ModelSearchListingExcel> listingRows = List.of();
        if (operation == ModelSearchOperation.FETCH_PRICE) {
            var input = taskInputSnapshotStore.loadModelSearchPriceInput(task.getId());
            if (input.isEmpty() || input.get().isEmpty()) {
                return null;
            }
            priceRows = input.get();
        } else {
            var input = taskInputSnapshotStore.loadModelSearchListingInput(task.getId());
            if (input.isEmpty() || input.get().isEmpty()) {
                return null;
            }
            listingRows = input.get();
        }
        if (taskMapper.resumeTask(task.getId()) == 0) {
            return null;
        }
        try {
            startModelSearchRunner(account, task.getId(), operation, priceRows, listingRows);
            return task.getId();
        } catch (RuntimeException e) {
            taskMapper.updateTaskPaused(task.getId(), "任务恢复启动失败: " + e.getMessage());
            TaskSwitch.clearSearchListRunState(task.getId());
            throw e;
        }
    }

    public Long startSearchList(String accountId, String keywords, String sorts,
                                int pageCount, String searchType, int maxListCount,
                                boolean modelNoSearch) {
        return startSearchList(accountId, keywords, sorts, pageCount, searchType,
                maxListCount, modelNoSearch, Map.of());
    }

    public Long startSearchList(String accountId, String keywords, String sorts,
                                int pageCount, String searchType, int maxListCount,
                                boolean modelNoSearch, Map<String, Set<String>> modelNoSizeFilters) {
        sorts = defaultIfBlank(sorts, "featured");
        pageCount = pageCount > 0 ? pageCount : 3;
        searchType = defaultIfBlank(searchType, "shoes");
        maxListCount = Math.max(maxListCount, 0);
        modelNoSizeFilters = modelNoSizeFilters != null ? modelNoSizeFilters : Map.of();
        if (modelNoSearch) {
            sorts = "featured";
            pageCount = 1;
        }
        if (keywords == null || keywords.isBlank()) {
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        String taskTypeCode = TaskTypeEnum.LISTING.getCode();
        JSONObject paramsJson = new JSONObject()
                .fluentPut("keywords", keywords)
                .fluentPut("sorts", sorts)
                .fluentPut("pageCount", pageCount)
                .fluentPut("searchType", searchType)
                .fluentPut("maxListCount", maxListCount)
                .fluentPut("searchMode", modelNoSearch ? "model_no" : "keyword")
                .fluentPut("modelNoSearch", modelNoSearch);
        if (modelNoSearch && !modelNoSizeFilters.isEmpty()) {
            paramsJson.put("modelNoSizeFilters", modelNoSizeFilters);
        }
        String params = paramsJson.toJSONString();
        Long taskId = null;
        try {
            taskId = createTask("stockx", taskTypeCode, account.getName(), params);
            startSearchListRunner(account, taskId, keywords, sorts, pageCount, searchType,
                    maxListCount, modelNoSearch, modelNoSizeFilters);
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务启动失败: " + e.getMessage());
                TaskSwitch.clearSearchListRunState(taskId);
            }
            throw e;
        }
    }

    /**
     * 按 Excel 货号清单启动搜索上架。货号清单可能达到数万行，只把行数写进任务参数，
     * 完整清单落快照文件，避免撑爆 task.params 字段并保证暂停恢复、重跑可复用原始输入。
     */
    public Long startModelNoSearchList(String accountId, List<ModelNoSearchExcel> rows,
                                       String searchType, int maxListCount) {
        List<ModelNoSearchExcel> input = rows != null ? List.copyOf(rows) : List.of();
        String keywords = joinModelNos(input);
        if (keywords.isBlank()) {
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        searchType = defaultIfBlank(searchType, "shoes");
        maxListCount = Math.max(maxListCount, 0);
        String params = new JSONObject()
                .fluentPut("sorts", "featured")
                .fluentPut("pageCount", 1)
                .fluentPut("searchType", searchType)
                .fluentPut("maxListCount", maxListCount)
                .fluentPut("searchMode", "model_no")
                .fluentPut("modelNoSearch", true)
                .fluentPut("modelNoCount", keywords.split("\\n").length)
                .toJSONString();
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.LISTING.getCode(), account.getName(), params);
            taskInputSnapshotStore.saveSearchModelNoInput(taskId, input);
            startSearchListRunner(account, taskId, keywords, "featured", 1, searchType,
                    maxListCount, true, ModelNoSearchSizeFilter.build(input));
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null) {
                taskMapper.updateTaskFailed(taskId, "任务启动失败: " + e.getMessage());
                TaskSwitch.clearSearchListRunState(taskId);
            }
            throw e;
        }
    }

    private void startSearchListRunner(StockXAccount account, Long taskId, String keywords, String sorts,
                                       int pageCount, String searchType, int maxListCount,
                                       boolean modelNoSearch, Map<String, Set<String>> modelNoSizeFilters) {
        TaskSwitch.markSearchListRunning(taskId);
        TaskSwitch.resetSearchListCancel(taskId);
        TaskSwitch.resetSearchVerification(taskId);
        StockXSearchListTaskRunner runner = new StockXSearchListTaskRunner(
                account, taskId, keywords, sorts, pageCount, searchType, maxListCount, modelNoSearch,
                modelNoSizeFilters, stockXService, taskMapper);
        new Thread(runner, "StockX-SearchList-" + account.getName() + "-" + taskId).start();
        log.info("搜索上架任务已启动: [{}]", account.getName());
    }

    private String joinModelNos(List<ModelNoSearchExcel> rows) {
        return rows.stream()
                .filter(row -> row != null && row.getModelNo() != null && !row.getModelNo().isBlank())
                .map(row -> row.getModelNo().trim())
                .distinct()
                .collect(Collectors.joining("\n"));
    }

    private Long resumeSearchList(TaskDO task, JSONObject params, boolean modelNoSearch) {
        String accountId = task.getAccountName();
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            return null;
        }
        String keywords = params.getString("keywords");
        Map<String, Set<String>> sizeFilters = readModelNoSizeFilters(params);
        if (keywords == null && modelNoSearch) {
            var snapshot = taskInputSnapshotStore.loadSearchModelNoInput(task.getId());
            if (snapshot.isEmpty()) {
                return null;
            }
            keywords = joinModelNos(snapshot.get());
            sizeFilters = ModelNoSearchSizeFilter.build(snapshot.get());
        }
        if (keywords == null || keywords.isBlank()) {
            return null;
        }
        if (TaskSwitch.isSearchListRunning(task.getId())) {
            return null;
        }
        if (taskMapper.resumeTask(task.getId()) == 0) {
            return null;
        }
        try {
            startSearchListRunner(account, task.getId(), keywords,
                    defaultIfBlank(params.getString("sorts"), "featured"),
                    params.getIntValue("pageCount") > 0 ? params.getIntValue("pageCount") : 3,
                    defaultIfBlank(params.getString("searchType"), "shoes"),
                    Math.max(params.getIntValue("maxListCount"), 0), modelNoSearch,
                    sizeFilters);
            return task.getId();
        } catch (RuntimeException e) {
            taskMapper.updateTaskPaused(task.getId(), "任务恢复启动失败: " + e.getMessage());
            TaskSwitch.clearSearchListRunState(task.getId());
            throw e;
        }
    }

    private Map<String, Set<String>> readModelNoSizeFilters(JSONObject params) {
        JSONObject filtersJson = params.getJSONObject("modelNoSizeFilters");
        if (filtersJson == null || filtersJson.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> filters = new LinkedHashMap<>();
        for (String modelNo : filtersJson.keySet()) {
            JSONArray sizesJson = filtersJson.getJSONArray(modelNo);
            if (sizesJson == null || sizesJson.isEmpty()) {
                continue;
            }
            filters.put(modelNo, new LinkedHashSet<>(sizesJson.toJavaList(String.class)));
        }
        return filters;
    }

    private boolean isModelNoSearch(JSONObject params) {
        return params != null && (params.getBooleanValue("modelNoSearch")
                || "model_no".equals(params.getString("searchMode")));
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

    // ==================== StockX 下架 ====================

    public Long startExcelDelist(String accountId, String inventoryType) {
        return startDelist(accountId, inventoryType, DelistMode.EXCEL,
                List.copyOf(ShoesContext.getDelistList(accountId, defaultIfBlank(inventoryType, "STANDARD"))));
    }

    public Long startDelist(String accountId, String inventoryType, DelistMode mode) {
        List<StockXDelistInputExcel> input = mode == DelistMode.EXCEL
                ? List.copyOf(ShoesContext.getDelistList(accountId, defaultIfBlank(inventoryType, "STANDARD")))
                : List.of();
        return startDelist(accountId, inventoryType, mode, input);
    }

    private Long startDelist(String accountId, String inventoryType, DelistMode mode,
                             List<StockXDelistInputExcel> input) {
        inventoryType = defaultIfBlank(inventoryType, "STANDARD");
        mode = mode != null ? mode : DelistMode.EXCEL;
        String key = accountId + ":" + inventoryType;
        if (mode == DelistMode.EXCEL && (input == null || input.isEmpty())) {
            return null;
        }
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            log.error("账号不存在: {}", accountId);
            return null;
        }
        if (!TaskSwitch.tryStartExcelDelist(key)) {
            log.info("下架任务已在运行: {}", key);
            return null;
        }
        String params = new JSONObject()
                .fluentPut("inventoryType", inventoryType)
                .fluentPut("delistMode", mode.getCode())
                .toJSONString();
        Long taskId = null;
        try {
            taskId = createTask("stockx", TaskTypeEnum.EXCEL_DELIST.getCode(), account.getName(), params);
            List<StockXDelistInputExcel> snapshot = input != null ? List.copyOf(input) : List.of();
            if (mode == DelistMode.EXCEL) {
                taskInputSnapshotStore.saveDelist(taskId, snapshot);
            }
            TaskSwitch.setExcelDelistTaskId(key, taskId);
            TaskSwitch.resetExcelDelistCancel(key);

            StockXExcelDelistTaskRunner runner = new StockXExcelDelistTaskRunner(
                    account, taskId, inventoryType, mode, stockXClient, taskMapper, taskItemMapper,
                    taskInputSnapshotStore, 0, snapshot);
            new Thread(runner, "StockX-Delist-" + mode.getCode() + "-" + account.getName()
                    + "-" + inventoryType).start();
            log.info("下架任务已启动: [{}] {} mode:{}", account.getName(), inventoryType, mode.getCode());
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
        DelistMode mode = delistMode(params);
        String key = accountId + ":" + inventoryType;
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null) {
            return null;
        }
        var snapshot = taskInputSnapshotStore.loadDelist(task.getId());
        if (mode == DelistMode.EXCEL && (snapshot.isEmpty() || snapshot.get().isEmpty())) {
            return null;
        }
        if (!TaskSwitch.tryStartExcelDelist(key)) {
            return null;
        }
        List<StockXDelistInputExcel> input = snapshot.orElseGet(List::of);
        if (taskMapper.resumeTask(task.getId()) == 0) {
            TaskSwitch.setExcelDelistRunning(key, false);
            return null;
        }
        try {
            TaskSwitch.setExcelDelistTaskId(key, task.getId());
            TaskSwitch.resetExcelDelistCancel(key);
            StockXExcelDelistTaskRunner runner = new StockXExcelDelistTaskRunner(
                    account, task.getId(), inventoryType, mode, stockXClient, taskMapper, taskItemMapper,
                    taskInputSnapshotStore, task.getRound() != null ? task.getRound() : 0, input);
            new Thread(runner, "StockX-Delist-" + mode.getCode() + "-" + account.getName()
                    + "-" + inventoryType).start();
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
