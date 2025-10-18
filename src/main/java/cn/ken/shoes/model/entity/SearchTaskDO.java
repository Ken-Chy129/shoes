package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("search_task")
public class SearchTaskDO extends BaseDO {

    /**
     * 搜索关键词
     */
    private String query;

    /**
     * 排序规则，逗号分隔，如 "featured,lowest_ask"
     */
    private String sorts;

    /**
     * 每个sort查询的页数
     */
    private Integer pageCount;

    /**
     * 进度百分比 (0-100)
     */
    private Integer progress;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 生成的文件路径
     */
    private String filePath;

    /**
     * 任务开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    /**
     * 任务结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    @Getter
    public enum StatusEnum {
        PENDING("待执行", "pending"),
        RUNNING("执行中", "running"),
        SUCCESS("执行成功", "success"),
        FAILED("执行失败", "failed"),
        ;

        private final String name;
        private final String code;

        StatusEnum(String name, String code) {
            this.name = name;
            this.code = code;
        }

        public static StatusEnum from(String code) {
            for (StatusEnum value : StatusEnum.values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            return null;
        }
    }
}
