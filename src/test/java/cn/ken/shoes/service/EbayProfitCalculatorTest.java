package cn.ken.shoes.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class EbayProfitCalculatorTest {

    @Test
    void keepsExactProfitSeparateFromDisplayRounding() {
        assertThat(EbayProfitCalculator.exactProfit(new BigDecimal("36.40"), new BigDecimal("4"),
                new BigDecimal("6.7"), new BigDecimal("103"), new BigDecimal("25")))
                .isEqualByComparingTo("50.001044");
        assertThat(EbayProfitCalculator.profit(new BigDecimal("36.40"), new BigDecimal("4"),
                new BigDecimal("6.7"), new BigDecimal("103"), new BigDecimal("25")))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void computesBuyerPaysAndNetForHighPrice() {
        BigDecimal x = new BigDecimal("150");
        // y = 150 + 14.95 + 13.5 = 178.45
        assertThat(EbayProfitCalculator.buyerPays(x)).isEqualByComparingTo("178.45");
        // z = 150 - 178.45 * (0.07 + 0.013 + 0.04) = 150 - 21.94935 = 128.05065
        assertThat(EbayProfitCalculator.netReceived(x, new BigDecimal("4")))
                .isEqualByComparingTo("128.05065");
        // 128.05065 * 6.7 - 500 - 25 = 332.94
        assertThat(EbayProfitCalculator.profit(x, new BigDecimal("4"), new BigDecimal("6.7"),
                new BigDecimal("500"), new BigDecimal("25"))).isEqualByComparingTo("332.94");
    }

    @Test
    void usesHigherFinalValueFeeBelow150() {
        BigDecimal x = new BigDecimal("149.99");
        BigDecimal y = EbayProfitCalculator.buyerPays(x);
        BigDecimal expected = x.subtract(y.multiply(new BigDecimal("0.140")));
        assertThat(EbayProfitCalculator.netReceived(x, null)).isEqualByComparingTo(expected);
        assertThat(EbayProfitCalculator.netReceived(x, BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(expected.setScale(4, RoundingMode.HALF_UP));
    }

    @Test
    void promoSubsidyIsDeductedFromNetReceivedAtListPrice() {
        // 标价 $200，85 折，卖家承担 7 成 -> 补贴 = 200 × 15% × 70% = $21
        BigDecimal rate = new BigDecimal("0.105");
        BigDecimal base = EbayProfitCalculator.netReceived(new BigDecimal("200"), BigDecimal.ZERO);
        BigDecimal promo = EbayProfitCalculator.netReceived(new BigDecimal("200"), BigDecimal.ZERO, rate);
        assertThat(base.subtract(promo)).isEqualByComparingTo("21");
        // 150 门槛仍按标价判断：$150 走 7%，$149.99 走 12.7%
        assertThat(
                EbayProfitCalculator.netReceived(new BigDecimal("150"), BigDecimal.ZERO, rate))
                .isEqualByComparingTo(EbayProfitCalculator.netReceived(new BigDecimal("150"), BigDecimal.ZERO)
                        .subtract(new BigDecimal("15.75")));
        assertThat(
                EbayProfitCalculator.exactProfit(new BigDecimal("200"), BigDecimal.ZERO, rate,
                        new BigDecimal("7"), new BigDecimal("1000"), new BigDecimal("25")))
                .isEqualByComparingTo(EbayProfitCalculator.exactProfit(new BigDecimal("200"), BigDecimal.ZERO,
                        new BigDecimal("7"), new BigDecimal("1000"), new BigDecimal("25")).subtract(new BigDecimal("147")));
    }
}
