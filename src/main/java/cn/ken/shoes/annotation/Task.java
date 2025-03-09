package cn.ken.shoes.annotation;

import cn.ken.shoes.model.entity.TaskDO;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Task {

    @AliasFor("name")
    String value() default "";

    String name() default "";

    TaskDO.PlatformEnum platform();

    TaskDO.TaskTypeEnum taskType();

    TaskDO.OperateStatusEnum operateStatus();

}
