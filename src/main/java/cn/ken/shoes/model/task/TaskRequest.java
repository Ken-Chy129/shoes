package cn.ken.shoes.model.task;

import cn.ken.shoes.common.PageRequest;
import lombok.Data;

import java.util.Date;

@Data
public class TaskRequest extends PageRequest {

    private Integer taskType;

    private String platform;

    private Date startTime;

    private Date endTime;

    private Integer taskStatus;
}
