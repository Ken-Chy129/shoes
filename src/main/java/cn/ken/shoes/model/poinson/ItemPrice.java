package cn.ken.shoes.model.poinson;

import lombok.Data;

import java.util.List;

@Data
public class ItemPrice {

    private Long skuId;

    private List<Price> items;

    @Data
    private static class Price {
        private Integer lowestPrice;

        private Integer tradeType;
    }
}
