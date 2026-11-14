package cn.ken.shoes.order;

import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.task.StockXPendingOrderClassifier;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class StockXPendingOrderClassifierTest {
    private static final String SOLD_AT = "2026-07-16T03:04:05Z";

    @Test
    void matchesSameUtcMinuteAndNumericPrice() {
        StockXPendingOrderClassifier classifier = classifier(page(1, null,
                sale("200.00", "2026-07-16T03:04:59.999Z")));
        TaskItemDO item = order("variant-1", "USD", "200", SOLD_AT);

        classifier.enrich(item);

        assertThat(item.getReplenishmentOrder()).isFalse();
    }

    @Test
    void differentPriceOrMinuteAndEmptyHistoryAreReplenishmentOrders() {
        for (JSONObject history : List.of(page(0, null),
                page(1, null, sale("201", SOLD_AT)),
                page(1, null, sale("200", "2026-07-16T03:05:00Z")),
                page(1, null, sale("200", "2026-07-16T03:03:59Z")))) {
            TaskItemDO item = order("variant-1", "USD", "200", SOLD_AT);
            classifier(history).enrich(item);
            assertThat(item.getReplenishmentOrder()).isTrue();
        }
    }

    @Test
    void readsAcrossSameMinuteBoundaryAndReusesFetchedPages() {
        List<String> cursors = new ArrayList<>();
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((variant, currency, after, pageNumber) -> {
            cursors.add(after);
            return after == null
                    ? page(3, "next", sale("201", "2026-07-16T03:04:30Z"))
                    : page(3, null, sale("200", "2026-07-16T03:04:00Z"), sale("180", "2026-07-16T03:03:59Z"));
        }, () -> {});
        TaskItemDO first = order("variant-1", "USD", "200", SOLD_AT);
        TaskItemDO second = order("variant-1", "USD", "180", "2026-07-16T03:03:01Z");
        classifier.enrich(first);
        classifier.enrich(second);

        assertThat(first.getReplenishmentOrder()).isFalse();
        assertThat(second.getReplenishmentOrder()).isFalse();
        assertThat(cursors).containsExactly(null, "next");
    }

    @Test
    void stopsAfterCoveringTargetTimeButResumesForAnOlderOrder() {
        AtomicInteger calls = new AtomicInteger();
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((variant, currency, after, pageNumber) ->
                calls.getAndIncrement() == 0
                        ? page(2, "next", sale("190", "2026-07-16T03:03:59Z"))
                        : page(2, null, sale("150", "2026-01-01T00:00:00Z")), () -> {});
        TaskItemDO recent = order("variant-1", "USD", "200", SOLD_AT);
        classifier.enrich(recent);
        assertThat(recent.getReplenishmentOrder()).isTrue();
        assertThat(calls).hasValue(1);

        TaskItemDO older = order("variant-1", "USD", "150", "2026-01-01T00:00:00Z");
        classifier.enrich(older);
        assertThat(older.getReplenishmentOrder()).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test
    void separatesVariantAndCurrencyCaches() {
        List<String> requests = new ArrayList<>();
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((variant, currency, after, pageNumber) -> {
            requests.add(variant + ":" + currency);
            return requests.size() == 1 ? page(1, null, sale("200", SOLD_AT)) : page(0, null);
        }, () -> {});
        TaskItemDO usd = order("variant-1", "USD", "200", SOLD_AT);
        TaskItemDO hkd = order("variant-1", "HKD", "200", SOLD_AT);
        TaskItemDO otherSize = order("variant-2", "USD", "200", SOLD_AT);
        classifier.enrich(usd);
        classifier.enrich(hkd);
        classifier.enrich(otherSize);

        assertThat(usd.getReplenishmentOrder()).isFalse();
        assertThat(hkd.getReplenishmentOrder()).isTrue();
        assertThat(otherSize.getReplenishmentOrder()).isTrue();
        assertThat(requests).containsExactly("variant-1:USD", "variant-1:HKD", "variant-2:USD");
    }

    @Test
    void malformedOrIncompletePagesNeverMeanReplenishment() {
        List<JSONObject> badPages = List.of(
                new JSONObject(),
                new JSONObject().fluentPut("edges", new JSONArray()).fluentPut("pageInfo", new JSONObject()),
                page(1, null),
                page(2, null, sale("201", SOLD_AT)),
                page(1, null, new JSONObject()),
                page(1, null, sale("200", "bad-time")),
                page(1, null, sale("-1", SOLD_AT)),
                page(2, null, sale("100", "2026-07-16T03:04:04Z"), sale("200", SOLD_AT))
        );
        for (JSONObject badPage : badPages) {
            TaskItemDO item = order("variant-1", "USD", "200", SOLD_AT);
            classifier(badPage).enrich(item);
            assertThat(item.getReplenishmentOrder()).as(badPage.toJSONString()).isNull();
        }
    }

    @Test
    void repeatedCursorAndChangedTotalsAreUnknown() {
        for (boolean changedTotal : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((v, c, a, p) ->
                    page(changedTotal && calls.getAndIncrement() > 0 ? 9 : 10,
                            "same", sale("201", SOLD_AT)), () -> {});
            TaskItemDO item = order("variant-1", "USD", "200", SOLD_AT);
            classifier.enrich(item);
            assertThat(item.getReplenishmentOrder()).isNull();
        }
    }

    @Test
    void failuresAreUnknownAndAreNotRepeatedForSameVariant() {
        AtomicInteger calls = new AtomicInteger();
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((v, c, a, p) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("unavailable");
        }, () -> {});
        TaskItemDO first = order("variant-1", "USD", "200", SOLD_AT);
        TaskItemDO second = order("variant-1", "USD", "201", SOLD_AT);
        classifier.enrich(first);
        classifier.enrich(second);
        assertThat(first.getReplenishmentOrder()).isNull();
        assertThat(second.getReplenishmentOrder()).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    void missingRequiredOrderFieldsDoNotQueryHistory() {
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((v, c, a, p) -> {
            throw new AssertionError("must not query");
        }, () -> {});
        for (String missing : List.of("variant", "currency", "price", "time")) {
            TaskItemDO item = order("variant-1", "USD", "200", SOLD_AT);
            switch (missing) {
                case "variant" -> item.setProductId(null);
                case "currency" -> item.setCurrencyCode(null);
                case "price" -> item.setSalePrice(null);
                case "time" -> item.setSoldOn(null);
            }
            classifier.enrich(item);
            assertThat(item.getReplenishmentOrder()).isNull();
        }
    }

    @Test
    void cancellationDuringLookupIsNotSwallowed() {
        AtomicInteger checks = new AtomicInteger();
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier(
                (v, c, a, p) -> page(2, "next", sale("201", SOLD_AT)), () -> {
                    if (checks.incrementAndGet() >= 3) throw new IllegalStateException("cancelled");
                });
        assertThatThrownBy(() -> classifier.enrich(order("variant-1", "USD", "200", SOLD_AT)))
                .isInstanceOf(IllegalStateException.class).hasMessage("cancelled");
    }

    @Test
    void pageLimitLeavesUnknownAndPassesRealPageNumbers() {
        List<Integer> pages = new ArrayList<>();
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((v, c, after, pageNumber) -> {
            pages.add(pageNumber);
            return page(100, "cursor-" + pageNumber, sale("201", SOLD_AT));
        }, () -> {}, 3);
        TaskItemDO first = order("variant-1", "USD", "200", SOLD_AT);
        TaskItemDO second = order("variant-1", "USD", "199", SOLD_AT);
        classifier.enrich(first);
        classifier.enrich(second);

        assertThat(first.getReplenishmentOrder()).isNull();
        assertThat(second.getReplenishmentOrder()).isNull();
        assertThat(pages).containsExactly(1, 2, 3);
    }

    @Test
    void pageLimitStillAllowsMatchesAndCoveredTimesWithinFetchedPages() {
        StockXPendingOrderClassifier classifier = new StockXPendingOrderClassifier((v, c, after, pageNumber) ->
                pageNumber == 1
                        ? page(100, "next", sale("200", SOLD_AT))
                        : page(100, "more", sale("150", "2026-07-16T03:00:00Z")), () -> {}, 2);
        TaskItemDO matched = order("variant-1", "USD", "200", SOLD_AT);
        TaskItemDO covered = order("variant-1", "USD", "300", "2026-07-16T03:02:00Z");
        TaskItemDO older = order("variant-1", "USD", "300", "2026-07-15T00:00:00Z");
        classifier.enrich(matched);
        classifier.enrich(covered);
        classifier.enrich(older);

        assertThat(matched.getReplenishmentOrder()).isFalse();
        assertThat(covered.getReplenishmentOrder()).isTrue();
        assertThat(older.getReplenishmentOrder()).isNull();
    }

    private StockXPendingOrderClassifier classifier(JSONObject page) {
        return new StockXPendingOrderClassifier((v, c, a, p) -> page, () -> {});
    }

    static TaskItemDO order(String variant, String currency, String price, String time) {
        TaskItemDO item = new TaskItemDO();
        item.setProductId(variant);
        item.setCurrencyCode(currency);
        item.setSalePrice(new BigDecimal(price));
        item.setSoldOn(Date.from(Instant.parse(time)));
        return item;
    }

    static JSONObject sale(String price, String time) {
        return new JSONObject().fluentPut("amount", price).fluentPut("createdAt", time);
    }

    static JSONObject page(long total, String cursor, JSONObject... sales) {
        JSONArray edges = new JSONArray();
        for (JSONObject sale : sales) edges.add(new JSONObject().fluentPut("node", sale));
        return new JSONObject().fluentPut("edges", edges).fluentPut("pageInfo",
                new JSONObject().fluentPut("total", total).fluentPut("endCursor", cursor));
    }
}
