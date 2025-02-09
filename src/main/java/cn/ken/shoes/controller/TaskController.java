package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.TaskService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("task")
public class TaskController {

    @Resource
    private TaskService taskService;

    @GetMapping
    public Result<List<TaskDO>> queryTasks() {
        return Result.buildSuccess(taskService.queryEvents());
    }
}
