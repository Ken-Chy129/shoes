package cn.ken.shoes.stockx;

import cn.ken.shoes.model.stockx.StockXSale;
import cn.ken.shoes.model.stockx.StockXSaleStatistics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockXSaleStatisticsTest {
    @Test
    void calculatesWindowAverageMedianAndCount() {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        var window = StockXSaleStatistics.window(List.of(
                sale("90", now.minus(1, ChronoUnit.DAYS)), sale("100", now.minus(2, ChronoUnit.DAYS)),
                sale("200", now.minus(8, ChronoUnit.DAYS))), 7, now);
        assertThat(window.averagePrice()).isEqualByComparingTo("95");
        assertThat(window.medianPrice()).isEqualByComparingTo("95");
        assertThat(window.salesCount()).isEqualTo(2);
    }

    private static StockXSale sale(String amount, Instant at) {
        return new StockXSale(new BigDecimal(amount), at);
    }
}
