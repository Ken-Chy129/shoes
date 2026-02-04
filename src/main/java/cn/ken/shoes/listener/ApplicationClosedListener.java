package cn.ken.shoes.listener;

import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import java.util.Date;
import java.util.List;

public class ApplicationClosedListener implements ApplicationListener<ContextClosedEvent> {

    @Resource
    private TaskMapper taskMapper;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        TaskRequest taskRequest = new TaskRequest();
        taskRequest.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        List<TaskDO> taskDOS = taskMapper.selectByCondition(taskRequest);
        for (TaskDO taskDO : taskDOS) {
            TaskDO toUpdate = new TaskDO();
            toUpdate.setId(taskDO.getId());
            toUpdate.setEndTime(new Date());
            toUpdate.setStatus(TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateById(toUpdate);
        }
    }
}
