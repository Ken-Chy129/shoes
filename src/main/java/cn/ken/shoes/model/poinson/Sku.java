package cn.ken.shoes.model.poinson;

import lombok.Data;

@Data
public class Sku {

    private Long skuId;

    /**
     * 商品状态(1:上架，0:下架)
     */
    private Integer status;

    /**
     * 商品规格属性，形如"{"尺码":"42.5"}"
     */
    private String properties;

    /**
     * 商品条码
     */
    private String barcode;

    /**
     * 商家商品编码
     */
    private String merchantSkuCode;

    /**
     * 商家商品编码
     */
    private String otherMerchantSkuCode;

    /**
     * sku得物码（附加码）
     */
    private String extraCode;

    /**
     * 是否套装
     */
    private Boolean suitFlag;
}
