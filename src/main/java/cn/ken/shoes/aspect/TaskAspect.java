package cn.ken.shoes.aspect;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
@Slf4j
public class TaskAspect {

    @Resource
    private TaskMapper taskMapper;

    @Around("@annotation(cn.ken.shoes.annotation.Task) && @annotation(task)")
    public Object recordTask(ProceedingJoinPoint point, Task task) {
        String taskName = point.getTarget().getClass().getName() + "." + point.getSignature().getName();
        Map<String, String> attributes = new HashMap<>();
        attributes.put("taskName", taskName);

        TaskDO taskDO = build(task);
        taskDO.setAttributes(JSON.toJSONString(attributes));
        taskMapper.insert(taskDO);

        long startTime = System.currentTimeMillis();
        Object result = null;
        try {
            result = point.proceed();
            return result;
        } catch (Throwable e) {
            TaskDO update = new TaskDO();
            update.setId(taskDO.getId());
            update.setStatus(TaskDO.TaskStatusEnum.FAILED.getCode());
            update.setCost(TimeUtil.getCostMin(startTime));
            update.setEndTime(new Date());
            attributes.put("errorMsg", e.getMessage());
            update.setAttributes(JSON.toJSONString(attributes));
            taskMapper.updateById(update);
            e.printStackTrace();
            log.error("task-{} execute error, msg:{}", taskName, e.getMessage());
            return null;
        } finally {
            String cost = TimeUtil.getCostMin(startTime);
            TaskDO update = new TaskDO();
            update.setId(taskDO.getId());
            update.setStatus(TaskDO.TaskStatusEnum.SUCCESS.getCode());
            update.setEndTime(new Date());
            update.setCost(cost);
            if (result != null) {
                attributes.put("result", String.valueOf(result));
                update.setAttributes(JSON.toJSONString(attributes));
            }
            taskMapper.updateById(update);
            log.info("task-{} execute finish, cost:{}", taskName, cost);
        }
    }

    private TaskDO build(Task task) {
        TaskDO taskDO = new TaskDO();
        taskDO.setPlatform(task.platform().getCode());
        taskDO.setTaskType(task.taskType().getCode());
        taskDO.setOperateType(task.operateStatus().getCode());
        taskDO.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        taskDO.setStartTime(new Date());
        return taskDO;
    }
}
