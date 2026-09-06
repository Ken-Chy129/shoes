package cn.ken.shoes.purchase;

import cn.ken.shoes.model.stockx.StockXBidFeePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXBidFeePolicyTest {

    @Test
    void calculatesTheProfitableBidLimitFromTheConfiguredFees() {
        StockXBidFeePolicy policy = new StockXBidFeePolicy(true,
                new BigDecimal("0.07"), new BigDecimal("5.79"),
                new BigDecimal("0.03"), false);

        assertThat(policy.merchantFee(new BigDecimal("100"))).isEqualByComparingTo("7.00");
        assertThat(policy.transferFee(new BigDecimal("100"))).isEqualByComparingTo("3.00");
        assertThat(policy.profitableBidLimit(new BigDecimal("100"))).isEqualByComparingTo("90.00");
    }

    @Test
    void appliesTheMinimumMerchantFeeAndAllowsTheExactLimit() {
        StockXBidFeePolicy policy = StockXBidFeePolicy.monitored(false);

        assertThat(policy.merchantFee(new BigDecimal("50"))).isEqualByComparingTo("5.79");
        assertThat(policy.profitableBidLimit(new BigDecimal("50"))).isEqualByComparingTo("42.71");
        assertThat(policy.isProfitable(new BigDecimal("42.71"), new BigDecimal("50"))).isTrue();
        assertThat(policy.isProfitable(new BigDecimal("42.72"), new BigDecimal("50"))).isFalse();
    }

    @Test
    void suppliesSafeDefaultsAndParsesExcelBooleanValues() {
        StockXBidFeePolicy disabled = StockXBidFeePolicy.disabled();

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.merchantFeeRate()).isEqualByComparingTo("0.07");
        assertThat(disabled.minMerchantFee()).isEqualByComparingTo("5.79");
        assertThat(disabled.transferFeeRate()).isEqualByComparingTo("0.03");
        assertThat(StockXBidFeePolicy.parseExcelEnabled("是")).isTrue();
        assertThat(StockXBidFeePolicy.parseExcelEnabled("TRUE")).isTrue();
        assertThat(StockXBidFeePolicy.parseExcelEnabled("1")).isTrue();
        assertThat(StockXBidFeePolicy.parseExcelEnabled("否")).isFalse();
        assertThat(StockXBidFeePolicy.parseExcelEnabled("false")).isFalse();
        assertThat(StockXBidFeePolicy.parseExcelEnabled("0")).isFalse();
        assertThat(StockXBidFeePolicy.parseExcelEnabled("")).isNull();
    }

    @Test
    void rejectsInvalidFeeParametersAndExcelFlags() {
        assertThatThrownBy(() -> new StockXBidFeePolicy(true,
                new BigDecimal("1.01"), BigDecimal.ZERO, BigDecimal.ZERO, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("商家手续费率");
        assertThatThrownBy(() -> StockXBidFeePolicy.parseExcelEnabled("开启"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("费率配置是否启用");
    }
}
