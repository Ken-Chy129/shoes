package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class TaskService {

    @Resource
    private TaskMapper taskMapper;

    public List<TaskDO> queryTasksByCondition(TaskRequest request) {
        return taskMapper.selectByCondition(request);
    }

    public Long startTask(String platform, TaskDO.TaskTypeEnum taskTypeEnum, Map<String, Object> attributeMap) {
        TaskDO taskDO = taskMapper.selectTask(taskTypeEnum.getCode(),platform, TaskDO.TaskStatusEnum.RUNNING.getCode());
        if (taskDO != null) {
            throw new RuntimeException("存在运行中的任务");
        }
        TaskDO newTaskDO = new TaskDO();
        newTaskDO.setPlatform(platform);
        newTaskDO.setType(taskTypeEnum.getCode());
        newTaskDO.setStartTime(new Date());
        newTaskDO.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        newTaskDO.setAttributes(JSON.toJSONString(attributeMap));
        taskMapper.insert(newTaskDO);
        return newTaskDO.getId();
    }

    public void updateTaskStatus(Long id, TaskDO.TaskStatusEnum status) {
        taskMapper.updateTaskStatus(id, status.getCode());
    }
}
