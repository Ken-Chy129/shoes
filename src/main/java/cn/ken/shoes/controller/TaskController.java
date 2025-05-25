package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import cn.ken.shoes.service.TaskService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("task")
public class TaskController {

    @Resource
    private TaskService taskService;

    @GetMapping("page")
    public PageResult<List<TaskDO>> queryTasks(TaskRequest request) {
        return taskService.queryTasksByCondition(request);
    }

    @GetMapping("querySetting")
    public Result<JSONObject> querySetting() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("kcTaskInterval", TaskSwitch.KC_TASK_INTERVAL);
        return Result.buildSuccess(jsonObject);
    }

    @PostMapping("updateSetting")
    public Result<Boolean> updateSetting(@RequestBody JSONObject jsonObject) {
        TaskSwitch.KC_TASK_INTERVAL = jsonObject.getLong("kcTaskInterval");
        return Result.buildSuccess(true);
    }
}
