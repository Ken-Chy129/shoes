package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;

public record StockXPurchaseGuidance(BigDecimal recommendedBid,
                                     String referenceLabel,
                                     BigDecimal referencePrice,
                                     String activity,
                                     String trend,
                                     String reason) {
}
