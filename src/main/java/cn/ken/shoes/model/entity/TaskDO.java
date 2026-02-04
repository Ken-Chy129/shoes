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
     * 执行轮次
     */
    private Integer round;

    @Getter
    public enum TaskTypeEnum {
        REFRESH_ALL_ITEMS("全量刷新商品", "refreshAllItems"),
        REFRESH_INCREMENTAL_ITEMS("增量刷新商品", "refreshIncrementalItems"),
        REFRESH_ALL_PRICES("全量刷新价格", "refreshAllPrices"),
        REFRESH_INCREMENTAL_PRICES("增量刷新价格", "refreshIncrementalPrices"),
        EXTEND_ORDER("订单延期", "extendOrder"),
        CLEAR_NO_BENEFIT_ITEMS("下架非盈利商品", "clearNoBenefitItems"),
        ;

        private final String name;
        private final String code;

        TaskTypeEnum(String name, String code) {
            this.name = name;
            this.code = code;
        }

        public static TaskTypeEnum from(String code) {
            for (TaskTypeEnum value : TaskTypeEnum.values()) {
                if (value.code.equals(code)) {
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
        CANCEL("已取消", "cancel"),
        ;

        private final String name;
        private final String code;

        TaskStatusEnum(String name, String code) {
            this.name = name;
            this.code = code;
        }

        public static TaskStatusEnum from(String code) {
            for (TaskStatusEnum value : TaskStatusEnum.values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            return null;
        }
    }
}
