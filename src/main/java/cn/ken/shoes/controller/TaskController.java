package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import cn.ken.shoes.service.TaskService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

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
    public Result<Long> getCurrentTaskId(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        return Result.buildSuccess(taskExecutorManager.getCurrentTaskId(type));
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

    // ==================== StockX 任务配置 ====================

    @GetMapping("stockx/config")
    public Result<JSONObject> getStockXTaskConfig() {
        JSONObject config = new JSONObject();
        config.put("priceDownThreadCount", StockXSwitch.TASK_PRICE_DOWN_THREAD_COUNT);
        config.put("priceDownPerMinute", StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE);
        config.put("listingSort", StockXSwitch.TASK_LISTING_SORT);
        config.put("listingOrder", StockXSwitch.TASK_LISTING_ORDER);
        return Result.buildSuccess(config);
    }

    @PostMapping("stockx/config")
    public Result<Void> updateStockXTaskConfig(@RequestBody JSONObject config) {
        if (config.containsKey("priceDownThreadCount")) {
            StockXSwitch.TASK_PRICE_DOWN_THREAD_COUNT = config.getInteger("priceDownThreadCount");
        }
        if (config.containsKey("priceDownPerMinute")) {
            StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE = config.getInteger("priceDownPerMinute");
        }
        if (config.containsKey("listingSort")) {
            StockXSwitch.TASK_LISTING_SORT = config.getString("listingSort");
        }
        if (config.containsKey("listingOrder")) {
            StockXSwitch.TASK_LISTING_ORDER = config.getString("listingOrder");
        }
        StockXSwitch.saveConfig();
        return Result.buildSuccess();
    }

    @GetMapping("stockx/sortOptions")
    public Result<List<Map<String, String>>> getStockXSortOptions() {
        // 返回排序选项列表：sort_order 组合格式
        List<Map<String, String>> sortOptions = List.of(
                Map.of("value", "CREATED_AT_DESC", "label", "已创建 - 最新"),
                Map.of("value", "CREATED_AT_ASC", "label", "已创建 - 最旧"),
                Map.of("value", "ITEM_TITLE_ASC", "label", "名称 - A-Z"),
                Map.of("value", "ITEM_TITLE_DESC", "label", "名称 - Z-A"),
                Map.of("value", "BID_ASK_SPREAD_ASC", "label", "价差 - 最小"),
                Map.of("value", "BID_ASK_SPREAD_DESC", "label", "价差 - 最大"),
                Map.of("value", "PRICE_ASC", "label", "你的报价 - 最低"),
                Map.of("value", "PRICE_DESC", "label", "你的报价 - 最高"),
                Map.of("value", "LOWEST_ASK_ASC", "label", "最低报价 - 最低"),
                Map.of("value", "LOWEST_ASK_DESC", "label", "最低报价 - 最高"),
                Map.of("value", "HIGHEST_BID_ASC", "label", "立即出售 - 最低"),
                Map.of("value", "HIGHEST_BID_DESC", "label", "立即出售 - 最高"),
                Map.of("value", "SIZE_ASC", "label", "尺码 - 小-大"),
                Map.of("value", "SIZE_DESC", "label", "尺码 - 大-小"),
                Map.of("value", "UPDATED_AT_DESC", "label", "已更新 - 最新"),
                Map.of("value", "UPDATED_AT_ASC", "label", "已更新 - 最旧")
        );
        return Result.buildSuccess(sortOptions);
    }

}
