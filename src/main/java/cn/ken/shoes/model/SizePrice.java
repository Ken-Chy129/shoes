package cn.ken.shoes.model;

import lombok.Data;

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
     * kickScrew平台价格
     */
    private Integer kickScrewPrice;

    /**
     * 得物普通发货价格
     */
    private Integer poisonNormalPrice;

    /**
     * 得物急速现货价格
     */
    private Integer poisonFastPrice;

    /**
     * 得物闪电发货价格
     */
    private Integer poisonLightningPrice;

    /**
     * 利润
     */
    private Integer profit;
}
