package cn.ken.shoes.stockx;

import cn.ken.shoes.model.stockx.StockXPurchaseGuidance;
import cn.ken.shoes.model.stockx.StockXPurchaseGuidanceCalculator;
import cn.ken.shoes.model.stockx.StockXSaleWindow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StockXPurchaseGuidanceCalculatorTest {

    private final StockXPurchaseGuidanceCalculator calculator = new StockXPurchaseGuidanceCalculator();

    @Test
    void recommendsOneDollarAboveHighestBidWhenBelowThirtyDayMedian() {
        StockXPurchaseGuidance result = calculator.calculate(new BigDecimal("89"),
                window(7, "95", 4), window(30, "96", 12), window(90, "94", 40));

        assertThat(result.recommendedBid()).isEqualByComparingTo("90");
        assertThat(result.activity()).isEqualTo("活跃");
        assertThat(result.trend()).isEqualTo("平稳");
        assertThat(result.reason()).contains("当前最高求购价高$1").contains("30天成交中位价");
    }

    @Test
    void doesNotChaseWhenHighestBidAlreadyExceedsRecentTrades() {
        StockXPurchaseGuidance result = calculator.calculate(new BigDecimal("101"),
                window(7, "91", 2), window(30, "96", 8), window(90, "99", 30));

        assertThat(result.recommendedBid()).isEqualByComparingTo("96");
        assertThat(result.activity()).isEqualTo("一般");
        assertThat(result.trend()).isEqualTo("下跌");
        assertThat(result.reason()).contains("不建议追价");
    }

    @Test
    void fallsBackToNinetyDayAverageWhenThirtyDaySampleIsTooSmall() {
        StockXSaleWindow ninetyDays = new StockXSaleWindow(90, new BigDecimal("92"), null, 20, null, null);
        StockXPurchaseGuidance result = calculator.calculate(new BigDecimal("80"),
                null, window(30, "90", 2), ninetyDays);

        assertThat(result.recommendedBid()).isEqualByComparingTo("81");
        assertThat(result.activity()).isEqualTo("较少");
        assertThat(result.reason()).contains("90天平均成交价");
    }

    @Test
    void returnsNoRecommendationWithoutBidOrSaleReference() {
        assertThat(calculator.calculate(null, null, null, null).recommendedBid()).isNull();
        assertThat(calculator.calculate(null, null, null, null).activity()).isEqualTo("数据不足");
    }

    private static StockXSaleWindow window(int days, String median, int count) {
        BigDecimal value = new BigDecimal(median);
        return new StockXSaleWindow(days, value, value, count, null, null);
    }
}
