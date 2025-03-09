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

    private String platform;

    private String taskType;

    private String operateType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    private String cost;

    /**
     * 任务执行状态
     */
    private String status;

    /**
     * 扩展属性，json
     */
    private String attributes;

    @Getter
    public enum TaskTypeEnum {
        REFRESH_ALL_ITEMS("全量刷新商品", 1),
        REFRESH_INCREMENTAL_ITEMS("增量刷新商品", 2),
        REFRESH_ALL_PRICES("全量刷新价格", 3),
        REFRESH_INCREMENTAL_PRICES("增量刷新价格", 4),
        CHANGE_PRICES("改价", 0)
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
        RUNNING("运行中", "running"),
        SUCCESS("执行成功", "success"),
        FAILED("执行失败", "failed"),
        TERMINATED("执行终止", "terminated"),
        ;

        private final String name;
        private final String code;

        TaskStatusEnum(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    @Getter
    public enum OperateStatusEnum {
        MANUALLY("人工执行", "manually"),
        SYSTEM("系统触发", "system"),
        ;

        private final String name;
        private final String code;

        OperateStatusEnum(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    @Getter
    public enum PlatformEnum {
        POISON("得物", "poison"),
        KC("KickScrew", "kickscrew"),
        STOCKX("绿叉", "stockx"),
        ;

        private final String name;
        private final String code;

        PlatformEnum(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }
}
