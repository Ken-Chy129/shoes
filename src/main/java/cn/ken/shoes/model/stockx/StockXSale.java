package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;
import java.time.Instant;

public record StockXSale(BigDecimal amount, Instant createdAt) {
}
