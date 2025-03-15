package cn.ken.shoes.model.stockx;

import lombok.Data;

@Data
public class StockXPrice {

    private String productId;

    private String variantId;

    private String euSize;

    private String price;
}
