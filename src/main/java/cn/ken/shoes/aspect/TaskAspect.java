package cn.ken.shoes.aspect;

import cn.ken.shoes.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class TaskAspect {

    @Around("@annotation(cn.ken.shoes.annotation.Task)")
    public Object recordTask(ProceedingJoinPoint point) throws Throwable {
        long startTime = System.currentTimeMillis();
        String taskName = point.getSignature().getName();
        try {
            // 继续执行原方法
            return point.proceed();
        } catch (Exception e) {
            log.error("task execute error, task:{}, error:{}", taskName, e.getMessage());
            return null;
        } finally {
            log.info("task execute finish, task:{}, cost:{}", taskName, TimeUtil.getCostMin(startTime));
        }
    }
}
