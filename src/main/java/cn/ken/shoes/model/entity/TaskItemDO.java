package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 任务明细表
 */
@Data
@TableName("task_item")
public class TaskItemDO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 关联任务ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /**
     * 执行轮次
     */
    private Integer round;

    /**
     * 商品标题
     */
    private String title;

    /**
     * 上架ID
     */
    private String listingId;

    /**
     * 商品ID
     */
    private String productId;

    /**
     * 货号
     */
    private String styleId;

    /**
     * 尺码
     */
    private String size;

    /**
     * EU码
     */
    private String euSize;

    /**
     * 当前售价
     */
    private BigDecimal currentPrice;

    /**
     * 最低价
     */
    private BigDecimal lowestPrice;

    /**
     * 得物价格
     */
    private BigDecimal poisonPrice;

    /**
     * 得物3.5价格
     */
    @TableField("poison_35_price")
    private BigDecimal poison35Price;

    /**
     * 3.5利润
     */
    @TableField("profit_35")
    private BigDecimal profit35;

    /**
     * 3.5利润率
     */
    @TableField("profit_rate_35")
    private BigDecimal profitRate35;

    /**
     * 操作结果
     */
    private String operateResult;

    /**
     * 操作时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date operateTime;
}
