package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("task")
public class TaskDO {

    private Long id;

    private String name;

    private Date startTime;

    private Date endTime;

    /**
     * 0:正在运行，1：运行成功，2：运行失败，3：运行终止
     */
    private Integer status;

    /**
     * 扩展属性，json
     */
    private String attributes;
}
