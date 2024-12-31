package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@TableName("item_size_price")
@EqualsAndHashCode(callSuper = true)
public class ItemSizePriceDO extends BaseDO {

    /**
     * 货号
     */
    private String modelNumber;

    /**
     * sku
     */
    private Long skuId;

    /**
     * 欧码
     */
    private String euSize;

    /**
     * 男美码
     */
    @TableField("men_us_size")
    private String menUSSize;

    /**
     * 女美码
     */
    @TableField("women_us_size")
    private String womenUSSize;

    /**
     * 英码
     */
    private String ukSize;

    /**
     * jp（cm）
     */
    private String jpSize;

    /**
     * kickScrew平台价格(USD)
     */
    private BigDecimal kickScrewPrice;

    /**
     * 得物普通发货价格(RMB)
     */
    private BigDecimal poisonNormalPrice;

    /**
     * 得物急速现货价格(RMB)
     */
    private BigDecimal poisonFastPrice;

    /**
     * 得物闪电发货价格(RMB)
     */
    private BigDecimal poisonLightningPrice;

    /**
     * 利润
     */
    private BigDecimal profit;
}
