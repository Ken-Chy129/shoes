package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.Getter;

import java.util.Date;

@Data
@TableName("task")
public class TaskDO {

    private Long id;

    private Integer type;

    private String platform;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
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
        REFRESH_ALL_ITEMS("全量刷新商品", 1),
        REFRESH_INCREMENTAL_ITEMS("增量刷新商品", 2),
        REFRESH_PRICES("刷新商品价格", 3),
        CHANGE_PRICES("改价", 4)
        ;

        private final String name;
        private final int code;

        TaskTypeEnum(String name, int code) {
            this.name = name;
            this.code = code;
        }

        public static TaskTypeEnum from(int code) {
            for (TaskTypeEnum value : TaskTypeEnum.values()) {
                if (value.code == code) {
                    return value;
                }
            }
            return null;
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
