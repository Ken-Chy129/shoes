package cn.ken.shoes.model.stockx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

public final class StockXSaleStatistics {
    private StockXSaleStatistics() {}

    public static StockXSaleWindow window(List<StockXSale> sales, int days, Instant now) {
        List<BigDecimal> amounts = sales.stream()
                .filter(sale -> !sale.createdAt().isBefore(now.minus(days, ChronoUnit.DAYS)))
                .map(StockXSale::amount).sorted().toList();
        if (amounts.isEmpty()) return new StockXSaleWindow(days, null, null, 0, null, null);
        BigDecimal average = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
        int middle = amounts.size() / 2;
        BigDecimal median = amounts.size() % 2 == 1 ? amounts.get(middle)
                : amounts.get(middle - 1).add(amounts.get(middle))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new StockXSaleWindow(days, average, median, amounts.size(), amounts.getFirst(), amounts.getLast());
    }

    public static StockXSale latest(List<StockXSale> sales) {
        return sales.stream().max(Comparator.comparing(StockXSale::createdAt)).orElse(null);
    }
}
