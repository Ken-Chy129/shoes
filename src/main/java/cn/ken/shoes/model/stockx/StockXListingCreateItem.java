package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;

public record StockXListingCreateItem(String variantId, BigDecimal amount, int quantity) {
}
