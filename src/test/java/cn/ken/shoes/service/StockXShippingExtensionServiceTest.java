package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXShippingExtensionServiceTest {

    @Test
    void extendsOnlyUnrequestedOrdersAcrossAllPagesAndDeduplicatesAskIds() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(true, "cursor-1",
                ask("ask-already", "order-already", true, "2026-07-18T12:59:59.999Z"),
                ask("ask-new-1", "order-new-1", false, "2026-07-17T12:59:59.999Z"),
                ask("ask-new-1", "order-new-1", false, "2026-07-17T12:59:59.999Z")));
        client.pages.add(page(false, null,
                ask("ask-new-2", "order-new-2", false, "2026-07-18T12:59:59.999Z"),
                ask("ask-ship-today", "order-ship-today", false, "2026-07-16T23:59:59.999Z"),
                ask(null, "order-missing-ask", false, "2026-07-18T12:59:59.999Z")));
        StockXShippingExtensionService service = new StockXShippingExtensionService(
                client, Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC));

        StockXShippingExtensionService.ExtensionSummary summary =
                service.extendPendingOrdersForAccount(account("account-a"));

        assertThat(client.requestedAfter).containsExactly(null, "cursor-1");
        assertThat(client.extended).containsExactly("ask-new-1:order-new-1", "ask-new-2:order-new-2");
        assertThat(summary.scanned()).isEqualTo(6);
        assertThat(summary.alreadyExtended()).isEqualTo(1);
        assertThat(summary.extended()).isEqualTo(2);
        assertThat(summary.skipped()).isEqualTo(3);
        assertThat(summary.failed()).isZero();
    }

    @Test
    void continuesWithNextAccountWhenOneAccountQueryFails() {
        FakeStockXClient client = new FakeStockXClient();
        client.failAccount = "broken";
        client.pages.add(page(false, null,
                ask("ask-ok", "order-ok", false, "2099-01-01T00:00:00Z")));
        StockXShippingExtensionService service = new StockXShippingExtensionService(client);

        service.extendAccounts(List.of(account("broken"), account("healthy")));

        assertThat(client.extended).containsExactly("ask-ok:order-ok");
    }

    @Test
    void stopsCurrentAccountImmediatelyWhenTokenExpiresDuringExtension() {
        FakeStockXClient client = new FakeStockXClient();
        client.tokenExpiresOnExtend = true;
        client.pages.add(page(false, null,
                ask("ask-first", "order-first", false, "2099-01-01T00:00:00Z"),
                ask("ask-second", "order-second", false, "2099-01-01T00:00:00Z")));
        StockXShippingExtensionService service = new StockXShippingExtensionService(client);

        assertThatThrownBy(() -> service.extendPendingOrdersForAccount(account("expired-token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TOKEN_EXPIRED");
        assertThat(client.extended).containsExactly("ask-first:order-first");
    }

    private static StockXAccount account(String name) {
        StockXAccount account = new StockXAccount();
        account.setName(name);
        account.setCountry("US");
        account.setEnabled(true);
        return account;
    }

    private static JSONObject ask(String askId, String orderNumber, boolean extensionRequested, String dateToShipBy) {
        return new JSONObject(true)
                .fluentPut("id", askId)
                .fluentPut("orderNumber", orderNumber)
                .fluentPut("shippingExtensionRequested", extensionRequested)
                .fluentPut("dateToShipBy", dateToShipBy);
    }

    private static JSONObject page(boolean hasNextPage, String endCursor, JSONObject... nodes) {
        JSONArray edges = new JSONArray();
        for (JSONObject node : nodes) {
            edges.add(new JSONObject(true).fluentPut("node", node));
        }
        return new JSONObject(true)
                .fluentPut("edges", edges)
                .fluentPut("pageInfo", new JSONObject(true)
                        .fluentPut("hasNextPage", hasNextPage)
                        .fluentPut("endCursor", endCursor));
    }

    private static class FakeStockXClient extends StockXClient {
        private final Deque<JSONObject> pages = new ArrayDeque<>();
        private final List<String> requestedAfter = new ArrayList<>();
        private final List<String> extended = new ArrayList<>();
        private String failAccount;
        private boolean tokenExpiresOnExtend;

        @Override
        public JSONObject queryPendingAsks(String after, StockXAccount account) {
            requestedAfter.add(after);
            if (account.getName().equals(failAccount)) {
                throw new IllegalStateException("query failed");
            }
            return pages.removeFirst();
        }

        @Override
        public boolean extendShipDate(String orderId, String askId, StockXAccount account) {
            extended.add(askId + ":" + orderId);
            if (tokenExpiresOnExtend) {
                throw new IllegalStateException("TOKEN_EXPIRED");
            }
            return true;
        }
    }
}
