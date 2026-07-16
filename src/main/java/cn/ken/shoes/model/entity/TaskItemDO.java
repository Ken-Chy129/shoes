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
     * 品牌
     */
    private String brand;

    /**
     * 商品标题
     */
    private String title;

    /**
     * 上架ID
     */
    private String listingId;

    /**
     * 变体ID(variantId)：字段名沿用历史命名 productId，但实际存的是 StockX variantId（具体尺码维度）
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
     * StockX订单号
     */
    @TableField("order_number")
    private String orderNumber;

    /**
     * StockX订单状态（中文展示值）
     */
    @TableField("order_status")
    private String orderStatus;

    /**
     * 订单币种
     */
    @TableField("currency_code")
    private String currencyCode;

    /**
     * 出售价格
     */
    @TableField("sale_price")
    private BigDecimal salePrice;

    /**
     * 预计/实际货款
     */
    @TableField("payout_amount")
    private BigDecimal payoutAmount;

    /**
     * 出售日期
     */
    @TableField("sold_on")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date soldOn;

    /**
     * 当前售价
     */
    private BigDecimal currentPrice;

    /**
     * 压价目标价：本轮提交给 StockX 的新报价。用于校验/对账时比对 amount==目标价，
     * 判断 StockX 是否真按预期价生效(而非被下限钳制成别的价)。
     */
    @TableField("target_price")
    private BigDecimal targetPrice;

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
