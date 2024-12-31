package cn.ken.shoes.model.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class ItemSizePriceDO extends BaseDO {

    /**
     * 商品id
     */
    private Long itemId;

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
    private String memUSSize;

    /**
     * 女美码
     */
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
     * 汇率
     */
    private BigDecimal exchangeRate;

    /**
     * 利润
     */
    private BigDecimal profit;
}
