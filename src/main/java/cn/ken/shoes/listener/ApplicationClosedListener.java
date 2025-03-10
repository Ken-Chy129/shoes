package cn.ken.shoes.listener;

import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import com.alibaba.fastjson.JSONObject;
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
            toUpdate.setStatus(TaskDO.TaskStatusEnum.TERMINATED.getCode());
            JSONObject jsonObject = JSONObject.parseObject(taskDO.getAttributes());
            jsonObject.put("terminateReason", "application close");
            toUpdate.setAttributes(jsonObject.toJSONString());
            taskMapper.updateById(toUpdate);
        }
    }
}
