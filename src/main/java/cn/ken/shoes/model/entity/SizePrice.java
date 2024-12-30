package cn.ken.shoes.model.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SizePrice {

    /**
     * sku
     */
    private Long skuId;

    /**
     * 欧码
     */
    private String euSize;

    /**
     * 美码
     */
    private String usSize;

    /**
     * 英码
     */
    private String ukSize;

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
