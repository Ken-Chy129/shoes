package cn.ken.shoes.model.task;

import lombok.Data;

import java.util.Date;

@Data
public class TaskRequest {

    private Integer taskType;

    private String platform;

    private Date startTime;

    private Date endTime;

    private Integer taskStatus;
}
