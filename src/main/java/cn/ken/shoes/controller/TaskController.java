package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.excel.StockXPriceDownInputExcel;
import cn.ken.shoes.model.task.TaskRequest;
import cn.ken.shoes.service.TaskService;
import cn.ken.shoes.service.StockXShippingExtensionService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

@RestController
@RequestMapping("task")
public class TaskController {

    @Resource
    private TaskService taskService;

    @Resource
    private TaskExecutorManager taskExecutorManager;

    @Resource
    private ConfigManager configManager;

    @Resource
    private StockXShippingExtensionService shippingExtensionService;

    @GetMapping("page")
    public PageResult<List<TaskDO>> queryTasks(TaskRequest request) {
        return taskService.queryTasksByCondition(request);
    }

    @DeleteMapping("delete")
    public Result<Void> deleteTask(@RequestParam Long taskId) {
        taskService.deleteTask(taskId);
        return Result.buildSuccess();
    }

    @PostMapping("cancelById")
    public Result<Void> cancelTaskById(@RequestParam Long taskId) {
        taskService.cancelTaskById(taskId);
        return Result.buildSuccess();
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
        List<StockXPriceDownInputExcel> list = EasyExcel.read(file.getInputStream())
                .head(StockXPriceDownInputExcel.class)
                .sheet()
                .doReadSync();
        ShoesContext.loadPriceDownExcel(accountId, inventoryType, list);
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
                    "minPrice", config.minPrice()
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
        boolean hasExcel = body.getBooleanValue("hasExcel");
        boolean processOutside = body.getBooleanValue("processOutsideExcel");
        String unprofitableAction = body.getString("unprofitableAction");
        // 逐任务轮询间隔（秒）：缺省/<=0 时后端回退默认值
        long interval = body.getLongValue("interval");
        if (!hasExcel) {
            ShoesContext.getPriceDownMap(accountId, inventoryType).clear();
            // 同步清除持久化文件，避免重启后旧 Excel 数据复活
            configManager.deletePriceDownExcel(accountId, inventoryType);
        }
        TaskSwitch.setProcessOutsideExcel(accountId, inventoryType, processOutside);
        TaskSwitch.setUnprofitableAction(accountId, inventoryType, unprofitableAction != null ? unprofitableAction : "markup");
        taskExecutorManager.startExcelPriceDown(accountId, inventoryType, hasExcel, processOutside, unprofitableAction != null ? unprofitableAction : "markup", interval);
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
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    // ==================== StockX 货号搜索上架 ====================

    @PostMapping("stockx/startModelNoSearchList")
    public Result<String> startModelNoSearchList(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("accountId") String accountId,
                                                  @RequestParam(value = "maxListCount", required = false, defaultValue = "0") Integer maxListCount) throws IOException {
        List<ModelNoSearchExcel> list = EasyExcel.read(file.getInputStream())
                .head(ModelNoSearchExcel.class)
                .sheet()
                .doReadSync();
        String keywords = list.stream()
                .map(ModelNoSearchExcel::getModelNo)
                .filter(m -> m != null && !m.trim().isEmpty())
                .distinct()
                .collect(Collectors.joining("\n"));
        if (keywords.isEmpty()) {
            return Result.buildError("Excel中未找到有效货号");
        }
        Long taskId = taskExecutorManager.startSearchList(
                accountId, keywords, "featured", 1, "shoes", maxListCount, true);
        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
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

    // ==================== StockX Excel下架 ====================

    @PostMapping("stockx/uploadDelistExcel")
    public Result<Integer> uploadDelistExcel(@RequestParam("file") MultipartFile file,
                                              @RequestParam("accountId") String accountId,
                                              @RequestParam("inventoryType") String inventoryType) throws IOException {
        List<StockXDelistInputExcel> list = EasyExcel.read(file.getInputStream())
                .head(StockXDelistInputExcel.class)
                .sheet()
                .doReadSync();
        ShoesContext.loadDelistExcel(accountId, inventoryType, list);
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

    @PostMapping("stockx/startExcelDelist")
    public Result<String> startExcelDelist(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        if (ShoesContext.getDelistList(accountId, inventoryType).isEmpty()) {
            return Result.buildError("请先上传下架Excel");
        }
        Long taskId = taskExecutorManager.startExcelDelist(accountId, inventoryType);
        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
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

}
