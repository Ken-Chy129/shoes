package cn.ken.shoes.model;

import lombok.Data;

@Data
public class SizePrice {

    /**
     * sku
     */
    private Long skuId;

    /**
     * 尺码
     */
    private String size;

    /**
     * 价格
     */
    private Integer price;
}
