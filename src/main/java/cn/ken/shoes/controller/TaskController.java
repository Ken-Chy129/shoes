package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.excel.StockXPriceDownInputExcel;
import cn.ken.shoes.model.task.TaskRequest;
import cn.ken.shoes.service.TaskService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("task")
public class TaskController {

    @Resource
    private TaskService taskService;

    @Resource
    private TaskExecutorManager taskExecutorManager;

    @GetMapping("page")
    public PageResult<List<TaskDO>> queryTasks(TaskRequest request) {
        return taskService.queryTasksByCondition(request);
    }

    @DeleteMapping("delete")
    public Result<Void> deleteTask(@RequestParam Long taskId) {
        taskService.deleteTask(taskId);
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

    @GetMapping("status")
    public Result<Boolean> queryTaskStatus(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        return Result.buildSuccess(taskExecutorManager.queryTaskStatus(type));
    }

    @GetMapping("interval")
    public Result<Long> queryTaskInterval(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        return Result.buildSuccess(taskExecutorManager.getTaskInterval(type));
    }

    @GetMapping("currentTaskId")
    public Result<String> getCurrentTaskId(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        Long taskId = taskExecutorManager.getCurrentTaskId(type);
        return Result.buildSuccess(taskId != null ? String.valueOf(taskId) : null);
    }

    @GetMapping("currentRound")
    public Result<Integer> getCurrentRound(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        return Result.buildSuccess(taskExecutorManager.getCurrentRound(type));
    }

    @PostMapping("interval")
    public Result<Void> updateTaskInterval(@RequestParam String taskType, @RequestParam Long interval) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        taskExecutorManager.setTaskInterval(type, interval);
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
        return Result.buildSuccess(ShoesContext.getPriceDownMap(accountId, inventoryType).size());
    }

    @GetMapping("stockx/priceDownExcelCount")
    public Result<Integer> getPriceDownExcelCount(@RequestParam("accountId") String accountId,
                                                  @RequestParam("inventoryType") String inventoryType) {
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
        boolean processOutside = body.getBooleanValue("processOutsideExcel");
        String unprofitableAction = body.getString("unprofitableAction");
        TaskSwitch.setProcessOutsideExcel(accountId, inventoryType, processOutside);
        TaskSwitch.setUnprofitableAction(accountId, inventoryType, unprofitableAction != null ? unprofitableAction : "markup");
        taskExecutorManager.startExcelPriceDown(accountId, inventoryType);
        return Result.buildSuccess(true);
    }

    @PostMapping("stockx/cancelExcelPriceDown")
    public Result<Boolean> cancelExcelPriceDown(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        taskExecutorManager.cancelExcelPriceDown(accountId, inventoryType);
        return Result.buildSuccess(true);
    }

    @GetMapping("stockx/excelPriceDownStatus")
    public Result<JSONObject> getExcelPriceDownStatus(@RequestParam("accountId") String accountId,
                                                      @RequestParam("inventoryType") String inventoryType) {
        JSONObject status = new JSONObject();
        status.put("running", taskExecutorManager.isExcelPriceDownRunning(accountId, inventoryType));
        Long taskId = taskExecutorManager.getExcelPriceDownTaskId(accountId, inventoryType);
        status.put("taskId", taskId != null ? String.valueOf(taskId) : null);
        status.put("interval", taskExecutorManager.getExcelPriceDownInterval(accountId, inventoryType) / 1000);
        return Result.buildSuccess(status);
    }

    @PostMapping("stockx/setExcelPriceDownInterval")
    public Result<Boolean> setExcelPriceDownInterval(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        long intervalSeconds = body.getLongValue("interval");
        taskExecutorManager.setExcelPriceDownInterval(accountId, inventoryType, intervalSeconds * 1000);
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
                maxListCount != null ? maxListCount : 0);

        if (taskId == null) {
            return Result.buildError("任务已在运行或账号不存在");
        }
        return Result.buildSuccess(String.valueOf(taskId));
    }

    @PostMapping("stockx/cancelSearchList")
    public Result<Boolean> cancelSearchList(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        if (StrUtil.isBlank(accountId)) {
            return Result.buildError("accountId不能为空");
        }
        taskExecutorManager.cancelSearchList(accountId);
        return Result.buildSuccess(true);
    }

    @GetMapping("stockx/searchListStatus")
    public Result<JSONObject> getSearchListStatus(@RequestParam("accountId") String accountId) {
        JSONObject status = new JSONObject();
        status.put("running", taskExecutorManager.isSearchListRunning(accountId));
        Long taskId = taskExecutorManager.getSearchListTaskId(accountId);
        status.put("taskId", taskId != null ? String.valueOf(taskId) : null);
        return Result.buildSuccess(status);
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

    @PostMapping("stockx/cancelFetchListings")
    public Result<Boolean> cancelFetchListings(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        taskExecutorManager.cancelFetchListings(accountId, inventoryType);
        return Result.buildSuccess(true);
    }

    @GetMapping("stockx/fetchListingsStatus")
    public Result<JSONObject> getFetchListingsStatus(@RequestParam("accountId") String accountId,
                                                      @RequestParam("inventoryType") String inventoryType) {
        JSONObject status = new JSONObject();
        status.put("running", taskExecutorManager.isFetchListingsRunning(accountId, inventoryType));
        Long taskId = taskExecutorManager.getFetchListingsTaskId(accountId, inventoryType);
        status.put("taskId", taskId != null ? String.valueOf(taskId) : null);
        return Result.buildSuccess(status);
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

    @GetMapping("stockx/delistExcelCount")
    public Result<Integer> getDelistExcelCount(@RequestParam("accountId") String accountId,
                                                @RequestParam("inventoryType") String inventoryType) {
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

    @PostMapping("stockx/cancelExcelDelist")
    public Result<Boolean> cancelExcelDelist(@RequestBody JSONObject body) {
        String accountId = body.getString("accountId");
        String inventoryType = body.getString("inventoryType");
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(inventoryType)) {
            return Result.buildError("accountId和inventoryType不能为空");
        }
        taskExecutorManager.cancelExcelDelist(accountId, inventoryType);
        return Result.buildSuccess(true);
    }

    @GetMapping("stockx/excelDelistStatus")
    public Result<JSONObject> getExcelDelistStatus(@RequestParam("accountId") String accountId,
                                                    @RequestParam("inventoryType") String inventoryType) {
        JSONObject status = new JSONObject();
        status.put("running", taskExecutorManager.isExcelDelistRunning(accountId, inventoryType));
        Long taskId = taskExecutorManager.getExcelDelistTaskId(accountId, inventoryType);
        status.put("taskId", taskId != null ? String.valueOf(taskId) : null);
        return Result.buildSuccess(status);
    }

}
