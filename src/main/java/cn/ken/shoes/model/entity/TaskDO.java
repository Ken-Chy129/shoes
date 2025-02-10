package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Getter;

import java.util.Date;

@Data
@TableName("task")
public class TaskDO {

    private Long id;

    private String type;

    private String platform;

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

    @Getter
    public enum TaskTypeEnum {
        REFRESH_ALL_ITEMS("全量刷新商品"),
        REFRESH_INCREMENTAL_ITEMS("增量刷新商品"),
        REFRESH_PRICES("刷新商品价格"),
        CHANGE_PRICES("改价")
        ;

        private final String type;

        TaskTypeEnum(String type) {
            this.type = type;
        }
    }


    @Getter
    public enum TaskStatusEnum {
        RUNNING(1),
        SUCCESS(2),
        FAILED(3),
        TERMINATED(4),
        ;

        private final int code;

        TaskStatusEnum(int code) {
            this.code = code;
        }
    }
}
