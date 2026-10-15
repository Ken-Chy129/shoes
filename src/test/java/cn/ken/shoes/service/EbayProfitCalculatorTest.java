package cn.ken.shoes.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class EbayProfitCalculatorTest {

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
}
