package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.common.DelistMode;
import cn.ken.shoes.common.ModelSearchOperation;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.manager.StockXPriceRateStateManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingByModelExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.excel.StockXPriceDownInputExcel;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import cn.ken.shoes.model.excel.StockXBidUpdateInputExcel;
import cn.ken.shoes.model.task.TaskRequest;
import cn.ken.shoes.service.TaskService;
import cn.ken.shoes.service.StockXReplenishmentService;
import cn.ken.shoes.service.StockXShippingExtensionService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("task")
public class TaskController {

    private static final long MAX_BID_EXCEL_SIZE = 10 * 1024 * 1024L;

    @Resource
    private TaskService taskService;

    @Resource
    private TaskExecutorManager taskExecutorManager;

    @Resource
    private ConfigManager configManager;

    @Resource
    private StockXShippingExtensionService shippingExtensionService;

    @Resource
    private StockXReplenishmentService replenishmentService;

    @Resource
    private StockXPriceRateStateManager stockXPriceRateStateManager;

    @GetMapping("page")
    public PageResult<List<TaskDO>> queryTasks(TaskRequest request) {
        return taskService.queryTasksByCondition(request);
    }

    /** 仅展示本服务实际观察到的调用/429，不代表StockX官方剩余额度，也不用于本地主动拦截。 */
    @GetMapping("stockx/rateStatus")
    public Result<List<StockXPriceRateStateManager.Snapshot>> stockXRateStatus() {
        StockXConfig.getAccounts().forEach(account -> stockXPriceRateStateManager.snapshot(account.getName()));
        return Result.buildSuccess(stockXPriceRateStateManager.snapshots());
    }

    @DeleteMapping("delete")
    public Result<Void> deleteTask(@RequestParam Long taskId) {
        try {
            taskService.deleteTask(taskId);
            return Result.buildSuccess();
        } catch (IllegalStateException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @PostMapping("cancelById")
    public Result<Void> cancelTaskById(@RequestParam Long taskId) {
        taskService.cancelTaskById(taskId);
        return Result.buildSuccess();
    }

    @PostMapping("{taskId}/resume")
    public Result<String> resumeTask(@PathVariable Long taskId) {
        try {
            return Result.buildSuccess(String.valueOf(taskService.resumeTaskById(taskId)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @PostMapping("{taskId}/rerun")
    public Result<String> rerunTask(@PathVariable Long taskId) {
        try {
            return Result.buildSuccess(String.valueOf(taskService.rerunTaskById(taskId)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.buildError(e.getMessage());
        }
    }

    // ==================== 统一任务管理接口 ====================

    @PostMapping("start")
    public Result<Void> startTask(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        taskExecutorManager.startTask(type);
        return Result.buildSuccess();
    }

    @PostMapping("cancel")
    public Result<Void> cancelTask(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        taskExecutorManager.cancelTask(type);
        return Result.buildSuccess();
    }

    // ==================== StockX Excel 压价 ====================

    @PostMapping("stockx/uploadPriceDownExcel")
    public Result<Integer> uploadPriceDownExcel(@RequestParam("file") MultipartFile file,
                                                @RequestParam("accountId") String accountId,
                                                @RequestParam("inventoryType") String inventoryType) throws IOException {
        if (!"STANDARD".equals(inventoryType) && !"CUSTODIAL".equals(inventoryType)) {
            return Result.buildError("无效的库存类型: " + inventoryType);
        }
        if (StockXConfig.getAccount(accountId) == null) {
            return Result.buildError("账号不存在: " + accountId);
        }
        List<StockXPriceDownInputExcel> list = EasyExcel.read(file.getInputStream())
                .head(StockXPriceDownInputExcel.class)
                .sheet()
                .doReadSync();
        try {
            ShoesContext.loadPriceDownExcel(accountId, inventoryType, list);
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
        // 落盘持久化，供服务重启后恢复压价任务用
        configManager.savePriceDownExcel(accountId, inventoryType);
        return Result.buildSuccess(ShoesContext.getPriceDownMap(accountId, inventoryType).size());
    }

    @GetMapping("stockx/priceDownExcelData")
    public Result<List<Map<String, Object>>> getPriceDownExcelData(@RequestParam("accountId") String accountId,
                                                                   @RequestParam("inventoryType") String inventoryType) {
        List<Map<String, Object>> result = new ArrayList<>();
        ShoesContext.getPriceDownMap(accountId, inventoryType).forEach((key, config) -> {
            String[] parts = key.split(":");
            result.add(Map.of(
                    "styleId", parts[0],
                    "size", parts.length > 1 ? parts[1] : "",
                    "minPrice", config.minPrice(),
                    "priceDownType", config.type().getCode()
            ));
        });
        return Result.buildSuccess(result);
    }

    // ==================== StockX Excel 多账号压价任务控制 ====================

    @PostMapping("stockx/startExcelPriceDown")
    public Result<Boolean> startExcelPriceDown(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        if (!"STANDARD".equals(inventoryType) && !"CUSTODIAL".equals(inventoryType)) {
            return Result.buildError("无效的库存类型: " + inventoryType);
        }
        boolean hasExcel = body.getBooleanValue("hasExcel");
        boolean processOutside = body.getBooleanValue("processOutsideExcel");
        ListingFetchMode fetchMode = ListingFetchMode.fromCode(body.getString("listingFetchMode"));
        if (fetchMode == ListingFetchMode.EXCEL_SEARCH && !hasExcel) {
            return Result.buildError("按Excel货号搜索必须上传压价Excel");
        }
        String unprofitableAction = body.getString("unprofitableAction");
        // 逐任务轮询间隔（秒）：缺省/<=0 时后端回退默认值
        long interval = body.getLongValue("interval");
        Long taskId = taskExecutorManager.startExcelPriceDown(accountId, inventoryType, hasExcel, processOutside,
                unprofitableAction != null ? unprofitableAction : "markup", interval, fetchMode);
        if (taskId == null) {
            return Result.buildError("任务已在运行、账号不存在或Excel输入为空");
        }
        return Result.buildSuccess(true);
    }

    // ==================== StockX 搜索上架 ====================

    @PostMapping("stockx/startSearchList")
    public Result<String> startSearchList(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String keywords = body.getString("keywords");
        String sorts = body.getString("sorts");
        Integer pageCount = body.getInteger("pageCount");
        String searchType = body.getString("searchType");
        Integer maxListCount = body.getInteger("maxListCount");
        String searchMode = StrUtil.blankToDefault(body.getString("searchMode"), "keyword");

        if (!"keyword".equals(searchMode)) {
            return Result.buildError("无效的搜索模式: " + searchMode);
        }
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(keywords) || StrUtil.isBlank(sorts)) {
            return Result.buildError("accountId、keywords和sorts不能为空");
        }

        Long taskId = taskExecutorManager.startSearchList(
                accountId, keywords, sorts,
                pageCount != null ? pageCount : 3,
                searchType != null ? searchType : "shoes",
                maxListCount != null ? maxListCount : 0,
                false);

        if (taskId == null) {
            return Result.buildError("账号不存在或搜索内容为空");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    /**
     * 货号搜索上架：货号清单通过 Excel 上传，只需一列「货号」，可选填「尺码」限定上架尺码。
     * 相比输入框粘贴，几万行货号不会卡死页面，也不会撑爆任务参数字段。
     */
    @PostMapping("stockx/startModelNoSearchListByExcel")
    public Result<String> startModelNoSearchListByExcel(@RequestParam("file") MultipartFile file,
                                                        @RequestParam("accountId") String accountId,
                                                        @RequestParam(value = "searchType", required = false,
                                                                defaultValue = "shoes") String searchType,
                                                        @RequestParam(value = "maxListCount", required = false,
                                                                defaultValue = "0") Integer maxListCount)
            throws IOException {
        if (StrUtil.isBlank(accountId)) {
            return Result.buildError("accountId不能为空");
        }
        List<ModelNoSearchExcel> rows = EasyExcel.read(file.getInputStream())
                .head(ModelNoSearchExcel.class)
                .sheet()
                .doReadSync();
        if (rows == null || rows.isEmpty()) {
            return Result.buildError("Excel中未找到数据");
        }
        Long taskId = taskExecutorManager.startModelNoSearchList(accountId, rows, searchType,
                maxListCount != null ? maxListCount : 0);
        if (taskId == null) {
            return Result.buildError("账号不存在或Excel中未找到有效货号");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    // ==================== StockX 货号搜索上架 ====================

    @PostMapping("stockx/startModelNoSearchList")
    public Result<String> startModelNoSearchList(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("accountId") String accountId,
                                                  @RequestParam(value = "operation", required = false,
                                                          defaultValue = "fetch_price") String operationCode) throws IOException {
        ModelSearchOperation operation = ModelSearchOperation.fromCode(operationCode);
        if (operation == null) {
            return Result.buildError("无效的操作类型: " + operationCode);
        }
        Long taskId;
        if (operation == ModelSearchOperation.FETCH_PRICE) {
            List<ModelNoSearchExcel> list = EasyExcel.read(file.getInputStream())
                    .head(ModelNoSearchExcel.class)
                    .sheet()
                    .doReadSync();
            if (list == null || list.isEmpty()) {
                return Result.buildError("Excel中未找到数据");
            }
            boolean hasInvalidRow = list.stream().anyMatch(row -> row == null
                    || StrUtil.isBlank(row.getModelNo()) || StrUtil.isBlank(row.getSize()));
            if (hasInvalidRow) {
                return Result.buildError("获取最低价时，Excel中的货号和尺码均为必填");
            }
            taskId = taskExecutorManager.startModelSearchPriceFetch(accountId, list);
        } else if (operation == ModelSearchOperation.CREATE_LISTING) {
            List<ModelSearchListingExcel> list = EasyExcel.read(file.getInputStream())
                    .head(ModelSearchListingExcel.class)
                    .sheet()
                    .doReadSync();
            if (list == null || list.isEmpty()) {
                return Result.buildError("Excel中未找到上架数据");
            }
            taskId = taskExecutorManager.startModelSearchListing(accountId, list);
        } else {
            List<ModelSearchListingByModelExcel> list = EasyExcel.read(file.getInputStream())
                    .head(ModelSearchListingByModelExcel.class)
                    .sheet()
                    .doReadSync();
            if (list == null || list.isEmpty()) {
                return Result.buildError("Excel中未找到上架数据");
            }
            taskId = taskExecutorManager.startModelSearchListingByModel(accountId, list);
        }
        if (taskId == null) {
            return Result.buildError("账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    // ==================== StockX 获取上架商品 ====================

    @PostMapping("stockx/startFetchListings")
    public Result<String> startFetchListings(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        Long taskId = taskExecutorManager.startFetchListings(accountId, inventoryType);
        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    // ==================== StockX 订单延期 ====================

    @PostMapping("stockx/startShippingExtension")
    public Result<String> startShippingExtension(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        if (StrUtil.isBlank(accountId)) {
            return Result.buildError("accountId不能为空");
        }
        Long taskId = shippingExtensionService.startManualAccount(accountId);
        if (taskId == null) {
            return Result.buildError("任务正在运行、账号不存在或账号未启用");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    // ==================== StockX 补单 ====================

    @PostMapping("stockx/startReplenishment")
    public Result<String> startReplenishment(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String soldStartTime = body.getString("soldStartTime");
        String soldEndTime = body.getString("soldEndTime");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(soldStartTime) || StrUtil.isBlank(soldEndTime)) {
            return Result.buildError("accountId和售出时间范围不能为空");
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            Instant startTime = LocalDateTime.parse(soldStartTime, formatter)
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            Instant endTime = LocalDateTime.parse(soldEndTime, formatter)
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            if (!startTime.isBefore(endTime)) {
                return Result.buildError("售出开始时间必须早于结束时间");
            }
            Long taskId = replenishmentService.startManualAccount(accountId, startTime, endTime);
            if (taskId == null) {
                return Result.buildError("任务正在运行、账号不存在或账号未启用");
            }
            return Result.buildSuccess(String.valueOf(taskId));
        } catch (DateTimeParseException e) {
            return Result.buildError("售出时间格式应为yyyy-MM-dd HH:mm:ss");
        }
    }

    // ==================== StockX 下架 ====================

    @PostMapping("stockx/uploadDelistExcel")
    public Result<Integer> uploadDelistExcel(@RequestParam("file") MultipartFile file,
                                              @RequestParam("accountId") String accountId,
                                              @RequestParam("inventoryType") String inventoryType) throws IOException {
        if (StockXConfig.getAccount(accountId) == null) {
            return Result.buildError("账号不存在: " + accountId);
        }
        if (!"STANDARD".equals(inventoryType) && !"CUSTODIAL".equals(inventoryType)) {
            return Result.buildError("无效的库存类型: " + inventoryType);
        }
        List<StockXDelistInputExcel> list = EasyExcel.read(file.getInputStream())
                .head(StockXDelistInputExcel.class)
                .sheet()
                .doReadSync();
        try {
            ShoesContext.loadDelistExcel(accountId, inventoryType, list);
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
        configManager.saveDelistExcel(accountId, inventoryType);
        return Result.buildSuccess(ShoesContext.getDelistList(accountId, inventoryType).size());
    }

    @GetMapping("stockx/delistExcelData")
    public Result<List<Map<String, Object>>> getDelistExcelData(@RequestParam("accountId") String accountId,
                                                                 @RequestParam("inventoryType") String inventoryType) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var item : ShoesContext.getDelistList(accountId, inventoryType)) {
            result.add(Map.of(
                    "listingId", item.getListingId() != null ? item.getListingId() : "",
                    "styleId", item.getStyleId() != null ? item.getStyleId() : "",
                    "size", item.getSize() != null ? item.getSize() : ""
            ));
        }
        return Result.buildSuccess(result);
    }

    @PostMapping("stockx/startDelist")
    public Result<String> startDelist(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        if (!"STANDARD".equals(inventoryType) && !"CUSTODIAL".equals(inventoryType)) {
            return Result.buildError("无效的库存类型: " + inventoryType);
        }
        DelistMode delistMode = DelistMode.fromCode(body.getString("delistMode"));
        if (delistMode == null) {
            return Result.buildError("无效的下架类型: " + body.getString("delistMode"));
        }
        if (delistMode == DelistMode.EXCEL && ShoesContext.getDelistList(accountId, inventoryType).isEmpty()) {
            return Result.buildError("请先上传下架Excel");
        }
        Long taskId = taskExecutorManager.startDelist(accountId, inventoryType, delistMode);
        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    /** 兼容旧前端：未传模式时仍按 Excel 下架处理。 */
    @PostMapping("stockx/startExcelDelist")
    public Result<String> startExcelDelist(@RequestBody JSONObject body) {
        if (StrUtil.isBlank(body.getString("delistMode"))) {
            body.put("delistMode", DelistMode.EXCEL.getCode());
        }
        return startDelist(body);
    }

    // ==================== StockX 获取订单 ====================

    @PostMapping("stockx/startFetchOrders")
    public Result<String> startFetchOrders(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        var orderTypes = body.getJSONArray("orderTypes");
        if (StrUtil.isBlank(accountId) || orderTypes == null || orderTypes.isEmpty()) {
            return Result.buildError("accountId和orderTypes不能为空");
        }
        LinkedHashSet<StockXOrderCategory> categories = new LinkedHashSet<>();
        for (String code : orderTypes.toJavaList(String.class)) {
            StockXOrderCategory category = StockXOrderCategory.fromCode(code).orElse(null);
            if (category == null) {
                return Result.buildError("无效的订单类型: " + code);
            }
            categories.add(category);
        }
        Long taskId = taskExecutorManager.startFetchOrders(accountId, new ArrayList<>(categories));
        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    // ==================== StockX 购买 ====================

    @PostMapping("stockx/startPurchase")
    public Result<String> startPurchase(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        if (StrUtil.isBlank(accountId)) {
            return Result.buildError("accountId不能为空");
        }
        StockXPurchaseOperation operation = StockXPurchaseOperation.fromCode(body.getString("operation"));
        if (operation == null) {
            return Result.buildError("无效的购买操作: " + body.getString("operation"));
        }
        if (operation == StockXPurchaseOperation.CREATE_BIDS
                || operation == StockXPurchaseOperation.UPDATE_BIDS) {
            return Result.buildError(operation.getLabel() + "请使用Excel上传接口");
        }
        Long taskId = taskExecutorManager.startPurchase(accountId, operation);
        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    @PostMapping("stockx/startCreateBids")
    public Result<String> startCreateBids(@RequestParam("file") MultipartFile file,
                                          @RequestParam("accountId") String accountId) throws IOException {
        if (StrUtil.isBlank(accountId)) {
            return Result.buildError("accountId不能为空");
        }
        String fileError = validateBidExcelFile(file);
        if (fileError != null) {
            return Result.buildError(fileError);
        }
        List<StockXBidInputExcel> rows;
        try {
            rows = EasyExcel.read(file.getInputStream())
                    .head(StockXBidInputExcel.class)
                    .sheet()
                    .doReadSync();
        } catch (RuntimeException e) {
            return Result.buildError("无法读取Excel，请确认文件格式和表头正确");
        }
        String rowsError = validateBidRows(rows);
        if (rowsError != null) {
            return Result.buildError(rowsError);
        }
        Long taskId = taskExecutorManager.startCreateBids(accountId, rows);
        if (taskId == null) {
            return Result.buildError("任务已在运行、账号不存在或Excel输入为空");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    @PostMapping("stockx/startUpdateBids")
    public Result<String> startUpdateBids(@RequestParam("file") MultipartFile file,
                                          @RequestParam("accountId") String accountId) throws IOException {
        if (StrUtil.isBlank(accountId)) {
            return Result.buildError("accountId不能为空");
        }
        String fileError = validateBidExcelFile(file);
        if (fileError != null) {
            return Result.buildError(fileError);
        }
        List<StockXBidUpdateInputExcel> rows;
        try {
            rows = EasyExcel.read(file.getInputStream())
                    .head(StockXBidUpdateInputExcel.class)
                    .sheet()
                    .doReadSync();
        } catch (RuntimeException e) {
            return Result.buildError("无法读取Excel，请确认文件格式和表头为出价ID、价格");
        }
        String rowsError = validateBidUpdateRows(rows);
        if (rowsError != null) {
            return Result.buildError(rowsError);
        }
        Long taskId = taskExecutorManager.startUpdateBids(accountId, rows);
        if (taskId == null) {
            return Result.buildError("任务已在运行、账号不存在或Excel输入为空");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    private String validateBidExcelFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "请上传出价Excel";
        }
        if (file.getSize() > MAX_BID_EXCEL_SIZE) {
            return "出价Excel不能超过10MB";
        }
        String filename = StrUtil.blankToDefault(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            return "仅支持.xlsx或.xls格式";
        }
        return null;
    }

    private String validateBidRows(List<StockXBidInputExcel> rows) {
        if (rows == null || rows.isEmpty()) {
            return "Excel中未找到出价数据";
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            StockXBidInputExcel row = rows.get(index);
            int excelRow = index + 2;
            if (row == null || StrUtil.isBlank(row.getStyleId()) || StrUtil.isBlank(row.getSize())) {
                return "出价Excel第" + excelRow + "行的货号和尺码均为必填";
            }
            BigDecimal price = row.getPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                return "出价Excel第" + excelRow + "行的价格必须大于0";
            }
            if (price.stripTrailingZeros().scale() > 0) {
                return "出价Excel第" + excelRow + "行的价格必须为整数美元";
            }
            row.setStyleId(row.getStyleId().trim());
            row.setSize(row.getSize().trim());
            row.setPrice(price.stripTrailingZeros());
            String duplicateKey = row.getStyleId().toUpperCase(Locale.ROOT) + "\u0000"
                    + row.getSize().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            if (!seen.add(duplicateKey)) {
                return "出价Excel第" + excelRow + "行的货号和尺码重复";
            }
        }
        return null;
    }

    private String validateBidUpdateRows(List<StockXBidUpdateInputExcel> rows) {
        if (rows == null || rows.isEmpty()) {
            return "Excel中未找到修改出价数据";
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            StockXBidUpdateInputExcel row = rows.get(index);
            int excelRow = index + 2;
            if (row == null || StrUtil.isBlank(row.getBidId())) {
                return "修改出价Excel第" + excelRow + "行的出价ID必填";
            }
            BigDecimal price = row.getPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                return "修改出价Excel第" + excelRow + "行的价格必须大于0";
            }
            if (price.stripTrailingZeros().scale() > 0) {
                return "修改出价Excel第" + excelRow + "行的价格必须为整数美元";
            }
            row.setBidId(row.getBidId().trim());
            row.setPrice(price.stripTrailingZeros());
            if (!seen.add(row.getBidId().toLowerCase(Locale.ROOT))) {
                return "修改出价Excel第" + excelRow + "行的出价ID重复";
            }
        }
        return null;
    }

}
