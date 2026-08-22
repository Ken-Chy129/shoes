package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;

public record StockXBidCreateItem(String variantId, BigDecimal amount, String localizedSizeType) {
}
