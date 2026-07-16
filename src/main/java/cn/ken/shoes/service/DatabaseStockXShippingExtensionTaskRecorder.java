package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStockXShippingExtensionTaskRecorder implements StockXShippingExtensionTaskRecorder {

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;

    public DatabaseStockXShippingExtensionTaskRecorder(TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    @Override
    public Long start(TaskDO task) {
        taskMapper.insert(task);
        return task.getId();
    }

    @Override
    public void record(TaskItemDO item) {
        taskItemMapper.insert(item);
    }

    @Override
    public void updateProgress(Long taskId, int pageCount, String attributes) {
        taskMapper.updateTaskRound(taskId, pageCount);
        taskMapper.updateTaskAttributes(taskId, attributes);
    }

    @Override
    public void complete(Long taskId, String cost, String summary) {
        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        taskMapper.updateTaskCost(taskId, cost);
        taskMapper.updateTaskFailReason(taskId, summary);
    }

    @Override
    public void fail(Long taskId, String cost, String reason) {
        taskMapper.updateTaskFailed(taskId, reason);
        taskMapper.updateTaskCost(taskId, cost);
    }
}
