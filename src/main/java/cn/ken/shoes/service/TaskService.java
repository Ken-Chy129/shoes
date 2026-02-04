package cn.ken.shoes.service;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TaskService {

    @Resource
    private TaskMapper taskMapper;

    public PageResult<List<TaskDO>> queryTasksByCondition(TaskRequest request) {
        Long count = taskMapper.count(request);
        if (count == 0) {
            return PageResult.buildSuccess();
        }
        List<TaskDO> taskDOS = taskMapper.selectByCondition(request);
        for (TaskDO taskDO : taskDOS) {
            Optional.ofNullable(TaskDO.TaskTypeEnum.from(taskDO.getTaskType())).ifPresent(taskType -> taskDO.setTaskType(taskType.getName()));
            Optional.ofNullable(TaskDO.TaskStatusEnum.from(taskDO.getStatus())).ifPresent(status -> taskDO.setStatus(status.getName()));
        }
        PageResult<List<TaskDO>> result = PageResult.buildSuccess(taskDOS);
        result.setTotal(count);
        return result;
    }
}
