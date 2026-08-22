package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;

public record StockXBidUpdateItem(String id, BigDecimal amount, String deliveryOptionType,
                                  String currency, String checkoutType) {
}
