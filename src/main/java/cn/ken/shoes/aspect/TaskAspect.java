package cn.ken.shoes.aspect;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.excel.util.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Date;

@Aspect
@Component
@Slf4j
public class TaskAspect {

    @Resource
    private TaskMapper taskMapper;

    @Around("@annotation(cn.ken.shoes.annotation.Task) && @annotation(task)")
    public Object recordTask(ProceedingJoinPoint point, Task task) throws Throwable {
        String methodName = point.getSignature().getName();
        String className = point.getTarget().getClass().getName();
        String taskName = StringUtils.isBlank(task.name()) ? className + "." + methodName : task.name();
        log.info("task-{} execute start", taskName);
        TaskDO taskDO = build(task);
        taskMapper.insert(taskDO);
        long startTime = System.currentTimeMillis();
        try {
            // 继续执行原方法
            return point.proceed();
        } catch (Exception e) {
            TaskDO update = new TaskDO();
            update.setId(taskDO.getId());
            update.setStatus(TaskDO.TaskStatusEnum.FAILED.getCode());
            update.setCost(TimeUtil.getCostMin(startTime));
            update.setAttributes(e.getMessage());
            taskMapper.updateById(update);
            log.error("task-{} execute error, msg:{}", taskName, e.getMessage());
            return null;
        } finally {
            String cost = TimeUtil.getCostMin(startTime);
            TaskDO update = new TaskDO();
            update.setId(taskDO.getId());
            update.setStatus(TaskDO.TaskStatusEnum.SUCCESS.getCode());
            update.setCost(cost);
            taskMapper.updateById(update);
            log.info("task-{} execute finish, cost:{}", taskName, cost);
        }
    }

    private TaskDO build(Task task) {
        TaskDO taskDO = new TaskDO();
        taskDO.setPlatform(task.platform().getName());
        taskDO.setTaskType(task.taskType().getName());
        taskDO.setOperateType(task.operateStatus().getName());
        taskDO.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        taskDO.setStartTime(new Date());
        return taskDO;
    }
}
