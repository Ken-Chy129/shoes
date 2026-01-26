package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import cn.ken.shoes.service.TaskService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PostMapping("stop")
    public Result<Void> stopTask(@RequestParam String taskType) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        taskExecutorManager.stopTask(type);
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

    @PostMapping("interval")
    public Result<Void> updateTaskInterval(@RequestParam String taskType, @RequestParam Long interval) {
        TaskTypeEnum type = TaskTypeEnum.fromCode(taskType);
        if (type == null) {
            return Result.buildError("无效的任务类型: " + taskType);
        }
        taskExecutorManager.setTaskInterval(type, interval);
        return Result.buildSuccess();
    }

}
