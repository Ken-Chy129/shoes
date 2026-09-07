package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;

public record StockXSaleWindow(int days,
                               BigDecimal averagePrice,
                               BigDecimal medianPrice,
                               Integer salesCount,
                               BigDecimal lowestPrice,
                               BigDecimal highestPrice) {
}
