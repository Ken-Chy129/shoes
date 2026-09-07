package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class StockXPurchaseGuidanceCalculator {

    private static final BigDecimal ONE_DOLLAR = BigDecimal.ONE;

    public StockXPurchaseGuidance calculate(BigDecimal highestBid, StockXSaleWindow sevenDays,
                                            StockXSaleWindow thirtyDays, StockXSaleWindow ninetyDays) {
        Reference reference = reference(thirtyDays, ninetyDays);
        String activity = activity(thirtyDays);
        String trend = trend(sevenDays, thirtyDays);
        if (highestBid == null || reference == null) {
            String reason = highestBid == null ? "暂无当前最高求购价" : "暂无可用成交价格";
            return new StockXPurchaseGuidance(null, reference != null ? reference.label : null,
                    reference != null ? reference.price : null, activity, trend, reason);
        }
        BigDecimal nextBid = highestBid.add(ONE_DOLLAR);
        if (nextBid.compareTo(reference.price) <= 0) {
            return new StockXPurchaseGuidance(nextBid, reference.label, reference.price, activity, trend,
                    "建议比当前最高求购价高$1，未超过" + reference.label);
        }
        return new StockXPurchaseGuidance(reference.price, reference.label, reference.price, activity, trend,
                "当前最高求购价已经接近或超过" + reference.label + "，不建议追价");
    }

    private static Reference reference(StockXSaleWindow thirtyDays, StockXSaleWindow ninetyDays) {
        if (thirtyDays != null && count(thirtyDays) >= 3 && thirtyDays.medianPrice() != null) {
            return new Reference("30天成交中位价", thirtyDays.medianPrice());
        }
        if (ninetyDays != null && ninetyDays.medianPrice() != null) {
            return new Reference("90天成交中位价", ninetyDays.medianPrice());
        }
        if (ninetyDays != null && ninetyDays.averagePrice() != null) {
            return new Reference("90天平均成交价", ninetyDays.averagePrice());
        }
        return null;
    }

    private static String activity(StockXSaleWindow thirtyDays) {
        if (thirtyDays == null || thirtyDays.salesCount() == null) return "数据不足";
        int count = Math.max(0, thirtyDays.salesCount());
        if (count >= 10) return "活跃";
        if (count >= 3) return "一般";
        if (count >= 1) return "较少";
        return "无近期成交";
    }

    private static String trend(StockXSaleWindow sevenDays, StockXSaleWindow thirtyDays) {
        BigDecimal seven = representative(sevenDays);
        BigDecimal thirty = representative(thirtyDays);
        if (seven == null || thirty == null || thirty.signum() == 0) return "数据不足";
        BigDecimal change = seven.subtract(thirty).divide(thirty, 4, RoundingMode.HALF_UP);
        if (change.compareTo(new BigDecimal("0.05")) > 0) return "上涨";
        if (change.compareTo(new BigDecimal("-0.05")) < 0) return "下跌";
        return "平稳";
    }

    private static BigDecimal representative(StockXSaleWindow window) {
        if (window == null) return null;
        return window.medianPrice() != null ? window.medianPrice() : window.averagePrice();
    }

    private static int count(StockXSaleWindow window) {
        return window.salesCount() != null ? window.salesCount() : 0;
    }

    private record Reference(String label, BigDecimal price) {}
}
