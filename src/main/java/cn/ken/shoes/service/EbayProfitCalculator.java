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
 *
 * <p>eBay 折扣活动（如买家 85 折、折扣部分卖家承担 7 成）时，卖家承担的补贴 = 补贴率 × x，
 * 会在之后从货款里扣回，因此到手额外减去这一项：z' = z - 补贴率·x（其余费用仍按标价 x 计算）。
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
        return netReceived(price, adRatePercent, BigDecimal.ZERO);
    }

    /**
     * 到手美元 z'，扣除卖家承担的活动补贴。
     *
     * @param sellerSubsidyRate 卖家承担的补贴占标价的比例（0.105 表示 10.5%），null 视为 0
     */
    public static BigDecimal netReceived(BigDecimal price, BigDecimal adRatePercent, BigDecimal sellerSubsidyRate) {
        BigDecimal subsidy = sellerSubsidyRate == null ? BigDecimal.ZERO : price.multiply(sellerSubsidyRate);
        return netReceivedBeforeSubsidy(price, adRatePercent).subtract(subsidy);
    }

    private static BigDecimal netReceivedBeforeSubsidy(BigDecimal price, BigDecimal adRatePercent) {
        BigDecimal y = buyerPays(price);
        BigDecimal fvf = price.compareTo(HIGH_PRICE_THRESHOLD) >= 0 ? HIGH_PRICE_FVF_RATE : LOW_PRICE_FVF_RATE;
        BigDecimal ad = adRatePercent == null ? BigDecimal.ZERO : adRatePercent.divide(HUNDRED);
        return price.subtract(y.multiply(fvf.add(INTERNATIONAL_FEE_RATE).add(ad)));
    }

    /** 利润（人民币）= z × 汇率 - 得物价 - 运费，保留两位小数。 */
    public static BigDecimal profit(BigDecimal price, BigDecimal adRatePercent, BigDecimal exchangeRate,
                                    BigDecimal poisonPrice, BigDecimal freight) {
        return exactProfit(price, adRatePercent, exchangeRate, poisonPrice, freight)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 未舍入的人民币利润：用于所有盈利判断及最低盈利价搜索；仅在展示/写明细时舍入。 */
    public static BigDecimal exactProfit(BigDecimal price, BigDecimal adRatePercent, BigDecimal exchangeRate,
                                         BigDecimal poisonPrice, BigDecimal freight) {
        return exactProfit(price, adRatePercent, BigDecimal.ZERO, exchangeRate, poisonPrice, freight);
    }

    /** 扣除卖家承担活动补贴后的未舍入人民币利润。 */
    public static BigDecimal exactProfit(BigDecimal price, BigDecimal adRatePercent, BigDecimal sellerSubsidyRate,
                                         BigDecimal exchangeRate, BigDecimal poisonPrice, BigDecimal freight) {
        return netReceived(price, adRatePercent, sellerSubsidyRate).multiply(exchangeRate)
                .subtract(poisonPrice).subtract(freight);
    }
}
