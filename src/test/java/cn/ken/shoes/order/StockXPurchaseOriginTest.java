package cn.ken.shoes.order;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.task.StockXOrderItemConverter;
import cn.ken.shoes.task.StockXPurchaseOrigin;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StockXPurchaseOriginTest {
    @Test
    void extractsBuyingSourceWithoutReplacingSalesOrder() {
        JSONObject node = JSONObject.parseObject("""
                {"associatedOrders":{
                  "standardizedSellOrder":{"orderNumber":"02-SALE"},
                  "flexIntakeOrder":{"sources":[
                    {"id":"other","type":"OTHER"},
                    {"id":"03-BUY","type":"BUY_INTO_FLEX"}]}}}
                """);
        TaskItemDO item = StockXOrderItemConverter.convert(1L, node, StockXOrderCategory.COMPLETED);
        assertThat(item.getOrderNumber()).isEqualTo("02-SALE");
        assertThat(item.getPurchaseOrderNumber()).isEqualTo("03-BUY");
        assertThat(StockXPurchaseOrigin.orderNumber(new JSONObject())).isNull();
    }

    @Test
    void loadsHistoryOnceAcrossPagesAndMatchesOrderIdNotChainOrSize() {
        List<String> cursors = new ArrayList<>();
        StockXPurchaseOrigin origin = new StockXPurchaseOrigin(cursor -> {
            cursors.add(cursor);
            return cursor == null ? page(true, "next", """
                    {"orderId":"03-A","orderNumber":"DISPLAY-A","chainId":"chain-A","amount":141,"currencyCode":"USD"}
                    """) : page(false, null, """
                    {"orderNumber":"03-B","chainId":"chain-B","amount":102.50,"currencyCode":"HKD"}
                    """);
        }, () -> {});
        TaskItemDO first = item("03-A");
        TaskItemDO second = item("03-B");
        TaskItemDO missing = item("chain-A");
        origin.enrich(first);
        origin.enrich(second);
        origin.enrich(missing);
        assertThat(first.getPurchasePrice()).isEqualByComparingTo("141");
        assertThat(first.getPurchaseCurrencyCode()).isEqualTo("USD");
        assertThat(second.getPurchasePrice()).isEqualByComparingTo("102.50");
        assertThat(second.getPurchaseCurrencyCode()).isEqualTo("HKD");
        assertThat(missing.getPurchaseOrderNumber()).isEqualTo("chain-A");
        assertThat(missing.getPurchasePrice()).isNull();
        assertThat(cursors).containsExactly(null, "next");
    }

    @Test
    void doesNotQueryForItemsWithoutBuyingSource() {
        StockXPurchaseOrigin origin = new StockXPurchaseOrigin(cursor -> {
            throw new AssertionError("Ordinary listings must not fetch buying history");
        }, () -> {});
        origin.enrich(item(null));
    }

    @Test
    void keepsTaskAccountIndexesIsolatedAndRejectsAmbiguousSources() {
        StockXPurchaseOrigin first = new StockXPurchaseOrigin(c -> page(false, null,
                "{\"orderId\":\"03-A\",\"amount\":141}"), () -> {});
        StockXPurchaseOrigin second = new StockXPurchaseOrigin(c -> page(false, null,
                "{\"orderId\":\"03-A\",\"amount\":99}"), () -> {});
        TaskItemDO a = item("03-A");
        TaskItemDO b = item("03-A");
        first.enrich(a);
        second.enrich(b);
        assertThat(a.getPurchasePrice()).isEqualByComparingTo("141");
        assertThat(b.getPurchasePrice()).isEqualByComparingTo("99");
        assertThatThrownBy(() -> StockXPurchaseOrigin.orderNumber(JSONObject.parseObject("""
                {"associatedOrders":{"flexIntakeOrder":{"sources":[
                  {"type":"BUY_INTO_FLEX","id":"03-A"},{"type":"BUY_INTO_FLEX","id":"03-B"}]}}}
                """))).hasMessageContaining("多个");
    }

    @Test
    void rejectsIncompletePagesAndRepeatedCursors() {
        StockXPurchaseOrigin incomplete = new StockXPurchaseOrigin(cursor -> new JSONObject(), () -> {});
        assertThatThrownBy(() -> incomplete.enrich(item("03-A")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("购买历史");
        StockXPurchaseOrigin repeated = new StockXPurchaseOrigin(cursor -> page(true, "same", ""), () -> {});
        assertThatThrownBy(() -> repeated.enrich(item("03-A")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("游标");
    }

    @Test
    void rejectsUnauthorizedAndHonorsCancellation() {
        StockXPurchaseOrigin unauthorized = new StockXPurchaseOrigin(
                cursor -> new JSONObject().fluentPut("_unauthorized", true), () -> {});
        assertThatThrownBy(() -> unauthorized.enrich(item("03-A"))).hasMessageContaining("Token");
        StockXPurchaseOrigin cancelled = new StockXPurchaseOrigin(cursor -> page(false, null, ""),
                () -> { throw new IllegalStateException("cancelled"); });
        assertThatThrownBy(() -> cancelled.enrich(item("03-A"))).hasMessage("cancelled");
    }

    private static TaskItemDO item(String order) {
        TaskItemDO item = new TaskItemDO();
        item.setPurchaseOrderNumber(order);
        return item;
    }

    private static JSONObject page(boolean more, String cursor, String node) {
        return JSONObject.parseObject("{\"edges\":[" + (node.isBlank() ? "" : "{\"node\":" + node + "}")
                + "],\"pageInfo\":{\"hasNextPage\":" + more + ",\"endCursor\":"
                + (cursor == null ? "null" : "\"" + cursor + "\"") + "}}");
    }
}
