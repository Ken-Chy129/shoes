package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test
    void recordsScheduledRunAndPerOrderResultsInTaskHistory() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(false, null,
                askWithProduct("ask-already", "listing-already", "order-already", true,
                        "2026-07-18T12:59:59.999Z", "Product A", "STYLE-A", "10", "EU 44"),
                askWithProduct("ask-new", "listing-new", "order-new", false,
                        "2026-07-18T12:59:59.999Z", "Product B", "STYLE-B", "9", "EU 43")));
        InMemoryTaskRecorder recorder = new InMemoryTaskRecorder();
        StockXShippingExtensionService service = new StockXShippingExtensionService(
                client, recorder,
                Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC));

        service.extendAccounts(List.of(account("account-a")), "scheduled");

        assertThat(recorder.task.getTaskType()).isEqualTo(TaskTypeEnum.EXTEND_SHIPPING.getCode());
        assertThat(recorder.task.getParams()).contains("scheduled");
        assertThat(recorder.items).extracting(TaskItemDO::getOperateResult)
                .containsExactly("已延期", "延期成功");
        assertThat(recorder.items.get(1).getTaskId()).isEqualTo(88L);
        assertThat(recorder.items.get(1).getListingId()).isEqualTo("ask-new");
        assertThat(recorder.items.get(1).getOrderNumber()).isEqualTo("order-new");
        assertThat(recorder.items.get(1).getTitle()).isEqualTo("Product B");
        assertThat(recorder.items.get(1).getStyleId()).isEqualTo("STYLE-B");
        assertThat(recorder.items.get(1).getSize()).isEqualTo("9");
        assertThat(recorder.items.get(1).getEuSize()).isEqualTo("43");
        assertThat(recorder.completed).isTrue();
        assertThat(recorder.attributes).contains("\"extended\":1");
    }

    @Test
    void manualTriggerCreatesTheSameTaskTypeAndRunsAsynchronously() throws InterruptedException {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(false, null));
        InMemoryTaskRecorder recorder = new InMemoryTaskRecorder();
        StockXShippingExtensionService service = new StockXShippingExtensionService(
                client, recorder,
                Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC));
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXConfig.setAccounts(List.of(account("account-a")));

            Long taskId = service.startManualAccount("account-a");

            assertThat(taskId).isEqualTo(88L);
            assertThat(recorder.finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(recorder.task.getTaskType()).isEqualTo(TaskTypeEnum.EXTEND_SHIPPING.getCode());
            assertThat(recorder.task.getParams()).contains("manual");
            assertThat(recorder.completed).isTrue();
        } finally {
            StockXConfig.setAccounts(originalAccounts);
        }
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

    private static JSONObject askWithProduct(String askId, String listingId, String orderNumber,
                                             boolean extensionRequested, String dateToShipBy,
                                             String title, String styleId, String size, String euSize) {
        JSONObject node = ask(askId, orderNumber, extensionRequested, dateToShipBy);
        node.put("listingId", listingId);
        node.put("productVariant", new JSONObject(true)
                .fluentPut("id", "variant-" + askId)
                .fluentPut("traits", new JSONObject(true).fluentPut("size", size))
                .fluentPut("sizeChart", new JSONObject(true).fluentPut("displayOptions", List.of(
                        Map.of("size", "US " + size), Map.of("size", euSize))))
                .fluentPut("product", new JSONObject(true)
                        .fluentPut("title", title)
                        .fluentPut("styleId", styleId)));
        return node;
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

    private static class InMemoryTaskRecorder implements StockXShippingExtensionTaskRecorder {
        private TaskDO task;
        private final List<TaskItemDO> items = new ArrayList<>();
        private String attributes;
        private boolean completed;
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public Long start(TaskDO task) {
            task.setId(88L);
            this.task = task;
            return task.getId();
        }

        @Override
        public void record(TaskItemDO item) {
            items.add(item);
        }

        @Override
        public void updateProgress(Long taskId, int pageCount, String attributes) {
            this.attributes = attributes;
        }

        @Override
        public void complete(Long taskId, String cost, String summary) {
            completed = true;
            finished.countDown();
        }

        @Override
        public void fail(Long taskId, String cost, String reason) {
            finished.countDown();
        }
    }
}
