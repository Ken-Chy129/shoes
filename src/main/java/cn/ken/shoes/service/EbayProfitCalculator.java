package cn.ken.shoes.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * eBay 含广告费的到手金额与利润计算。
 *
 * <pre>
 * 买家付款 y = x + 14.95 + 0.09x
 * 到手 z = x - 成交费率·y - 0.013·y - 广告费率·y      （x ≥ 150 时成交费率 7%，否则 12.7%）
 * 利润 = z × 汇率 - 得物价 - 运费
 * </pre>
 */
public final class EbayProfitCalculator {

    static final BigDecimal BUYER_SHIPPING = new BigDecimal("14.95");
    static final BigDecimal BUYER_TAX_RATE = new BigDecimal("0.09");
    static final BigDecimal HIGH_PRICE_THRESHOLD = new BigDecimal("150");
    static final BigDecimal HIGH_PRICE_FVF_RATE = new BigDecimal("0.07");
    static final BigDecimal LOW_PRICE_FVF_RATE = new BigDecimal("0.127");
    static final BigDecimal INTERNATIONAL_FEE_RATE = new BigDecimal("0.013");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private EbayProfitCalculator() {
    }

    /** 买家付款 y。 */
    public static BigDecimal buyerPays(BigDecimal price) {
        return price.add(BUYER_SHIPPING).add(price.multiply(BUYER_TAX_RATE));
    }

    /**
     * 到手美元 z。
     *
     * @param adRatePercent 广告费率百分数（4 表示 4%），null 视为 0
     */
    public static BigDecimal netReceived(BigDecimal price, BigDecimal adRatePercent) {
        BigDecimal y = buyerPays(price);
        BigDecimal fvf = price.compareTo(HIGH_PRICE_THRESHOLD) >= 0 ? HIGH_PRICE_FVF_RATE : LOW_PRICE_FVF_RATE;
        BigDecimal ad = adRatePercent == null ? BigDecimal.ZERO
                : adRatePercent.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return price.subtract(y.multiply(fvf.add(INTERNATIONAL_FEE_RATE).add(ad)));
    }

    /** 利润（人民币）= z × 汇率 - 得物价 - 运费，保留两位小数。 */
    public static BigDecimal profit(BigDecimal price, BigDecimal adRatePercent, BigDecimal exchangeRate,
                                    BigDecimal poisonPrice, BigDecimal freight) {
        return netReceived(price, adRatePercent).multiply(exchangeRate)
                .subtract(poisonPrice).subtract(freight)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
