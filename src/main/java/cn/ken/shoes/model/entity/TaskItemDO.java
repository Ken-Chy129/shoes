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

    /** eBay卖家自定义SKU。 */
    private String sku;

    /** eBay Inventory API Offer ID。 */
    private String offerId;

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
     * 已完成订单扣除各项费用后的最终货款
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
     * 本轮目标价格：StockX 压价任务或 eBay 定时改价任务计算出的目标价。
     */
    @TableField("target_price")
    private BigDecimal targetPrice;

    /**
     * 最低价
     */
    private BigDecimal lowestPrice;

    /**
     * Flex/寄存市场最低价
     */
    @TableField("flex_lowest_price")
    private BigDecimal flexLowestPrice;

    /** StockX 买家侧盘口第一档（最高求购价）。 */
    @TableField("highest_bid_price")
    private BigDecimal highestBidPrice;

    /** StockX 买家侧盘口第一档的求购数量。 */
    @TableField("highest_bid_count")
    private Integer highestBidCount;

    /** StockX 买家侧盘口第二档（次高求购价）。 */
    @TableField("second_highest_bid_price")
    private BigDecimal secondHighestBidPrice;

    /** StockX 买家侧盘口第二档的求购数量。 */
    @TableField("second_highest_bid_count")
    private Integer secondHighestBidCount;

    /**
     * 本次请求的上架数量
     */
    @TableField("listing_quantity")
    private Integer listingQuantity;

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
