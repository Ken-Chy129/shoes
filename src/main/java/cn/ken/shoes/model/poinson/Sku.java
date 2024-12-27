package cn.ken.shoes.model.poinson;

import lombok.Data;

@Data
public class Sku {

    private Long skuId;

    private Integer status;

    private String properties;

    private String barcode;

    private String merchantSkuCode;

    private String otherMerchantSkuCode;

    private String extraCode;

    private Boolean suitFlag;
}
