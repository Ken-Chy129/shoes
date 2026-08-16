package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXListingCreateItem;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StockXReplenishmentServiceTest {

    @Test
    void refreshesLatestPoisonPricesAndRelistsOnlyProfitableSalesInsideTheWindow() {
        FakeStockXClient client = new FakeStockXClient();
        client.pendingPages.add(cursorPage(false, null,
                pendingSale("ask-a", "order-a", "variant-a", "STYLE-A", "42", 200, 220,
                        "2026-08-05T01:00:00Z"),
                pendingSale("ask-b", "order-b", "variant-b", "STYLE-B", "44", 100, 110,
                        "2026-08-05T02:00:00Z"),
                pendingSale("ask-c", "order-c", "variant-c", "STYLE-C", "45", 250, 260,
                        "2026-08-05T03:00:00Z"),
                pendingSale("ask-old", "order-old", "variant-old", "STYLE-OLD", "43", 300, 310,
                        "2026-08-04T19:59:59Z")));

        FakePriceManager priceManager = new FakePriceManager(Map.of(
                "STYLE-A:42", 1,
                "STYLE-B:44", 1_000_000
        ));
        FakeStockXService stockXService = new FakeStockXService();
        InMemoryRecorder recorder = new InMemoryRecorder();
        StockXReplenishmentService service = new StockXReplenishmentService(
                client, priceManager, stockXService, recorder,
                Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC));

        StockXReplenishmentService.ReplenishmentSummary summary = service.replenishSoldItemsForAccount(
                account(), Instant.parse("2026-08-04T20:00:00Z"),
                Instant.parse("2026-08-05T08:00:00Z"), 88L);

        assertThat(priceManager.refreshedStyles).containsExactlyInAnyOrder("STYLE-A", "STYLE-B", "STYLE-C");
        assertThat(stockXService.submitted).hasSize(1);
        assertThat(stockXService.submitted.getFirst().variantId()).isEqualTo("variant-a");
        assertThat(stockXService.submitted.getFirst().amount()).isEqualByComparingTo("219");
        assertThat(stockXService.submitted.getFirst().quantity()).isEqualTo(1);
        assertThat(recorder.items).extracting(TaskItemDO::getOperateResult)
                .containsExactlyInAnyOrder("待上架", "跳过-不盈利", "跳过-得物无价");
        assertThat(summary.scanned()).isEqualTo(3);
        assertThat(summary.profitable()).isEqualTo(1);
        assertThat(summary.listingQuantity()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(2);
    }

    @Test
    void submitsRepeatedSalesOfTheSameVariantSeparatelyAtCurrentLowestAskMinusOne() {
        FakeStockXClient client = new FakeStockXClient();
        client.pendingPages.add(cursorPage(false, null,
                pendingSale("ask-new", "order-new", "variant-a", "STYLE-A", "42", 210, 230,
                        "2026-08-05T06:00:00Z"),
                pendingSale("ask-old", "order-old", "variant-a", "STYLE-A", "42", 200, 230,
                        "2026-08-05T05:00:00Z")));

        FakePriceManager priceManager = new FakePriceManager(Map.of("STYLE-A:42", 1));
        FakeStockXService stockXService = new FakeStockXService();
        InMemoryRecorder recorder = new InMemoryRecorder();
        StockXReplenishmentService service = new StockXReplenishmentService(
                client, priceManager, stockXService, recorder,
                Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC));

        StockXReplenishmentService.ReplenishmentSummary summary = service.replenishSoldItemsForAccount(
                account(), Instant.parse("2026-08-04T20:00:00Z"),
                Instant.parse("2026-08-05T08:00:00Z"), 88L);

        assertThat(stockXService.submitted).containsExactly(
                new StockXListingCreateItem("variant-a", new BigDecimal("229"), 1),
                new StockXListingCreateItem("variant-a", new BigDecimal("229"), 1));
        assertThat(stockXService.submissionBatchSizes).containsExactly(1, 1);
        assertThat(recorder.items).hasSize(2);
        assertThat(recorder.items).extracting(TaskItemDO::getOrderNumber)
                .containsExactly("order-new", "order-old");
        assertThat(recorder.items).extracting(TaskItemDO::getListingQuantity)
                .containsOnly(1);
        assertThat(recorder.items).extracting(TaskItemDO::getLowestPrice)
                .containsOnly(new BigDecimal("230"));
        assertThat(recorder.items).extracting(TaskItemDO::getTargetPrice)
                .containsOnly(new BigDecimal("229"));
        assertThat(summary.scanned()).isEqualTo(2);
        assertThat(summary.listingQuantity()).isEqualTo(2);
    }

    @Test
    void createsScheduledTaskWithThePastTwelveHourWindow() {
        FakeStockXClient client = new FakeStockXClient();
        client.pendingPages.add(cursorPage(false, null));
        InMemoryRecorder recorder = new InMemoryRecorder();
        StockXReplenishmentService service = new StockXReplenishmentService(
                client, new FakePriceManager(Map.of()), new FakeStockXService(), recorder,
                Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC));

        service.replenishAccounts(List.of(account()), Instant.parse("2026-08-04T20:00:00Z"),
                Instant.parse("2026-08-05T08:00:00Z"), "scheduled");

        assertThat(recorder.task.getTaskType()).isEqualTo(TaskTypeEnum.REPLENISHMENT.getCode());
        assertThat(recorder.task.getParams()).contains("scheduled", "2026-08-05 04:00:00", "2026-08-05 16:00:00");
        assertThat(recorder.completed).isTrue();
    }

    @Test
    void scheduledRunSkipsAccountsWithoutAutomaticReplenishmentEnabled() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount optedIn = account();
        optedIn.setName("opted-in");
        optedIn.setAutoReplenishmentEnabled(true);
        StockXAccount optedOut = account();
        optedOut.setName("opted-out");

        FakeStockXClient client = new FakeStockXClient();
        client.pendingPages.add(cursorPage(false, null));
        InMemoryRecorder recorder = new InMemoryRecorder();
        StockXReplenishmentService service = new StockXReplenishmentService(
                client, new FakePriceManager(Map.of()), new FakeStockXService(), recorder,
                Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC));

        try {
            StockXConfig.setAccounts(List.of(optedIn, optedOut));

            service.replenishAllEnabledAccountsLastHours(12, "scheduled");

            assertThat(recorder.task.getAccountName()).isEqualTo("opted-in");
            assertThat(recorder.completed).isTrue();
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        account.setCountry("US");
        account.setEnabled(true);
        account.setMinProfit(-30);
        return account;
    }

    private static JSONObject pendingSale(String askId, String orderNumber, String variantId,
                                          String styleId, String euSize, int amount,
                                          int lowestAsk, String soldOn) {
        JSONObject value = sale(askId, orderNumber, variantId, styleId, euSize, amount, soldOn);
        value.put("currentCurrency", "USD");
        value.getJSONObject("productVariant").put("market", new JSONObject(true).fluentPut("state",
                new JSONObject(true).fluentPut("askServiceLevels",
                        new JSONObject(true).fluentPut("standard",
                                new JSONObject(true).fluentPut("lowest",
                                        new JSONObject(true).fluentPut("amount", lowestAsk))))));
        return value;
    }

    private static JSONObject sale(String id, String orderNumber, String variantId,
                                   String styleId, String euSize, int amount, String soldOn) {
        return new JSONObject(true)
                .fluentPut("id", id)
                .fluentPut("orderNumber", orderNumber)
                .fluentPut("amount", amount)
                .fluentPut("soldOn", soldOn)
                .fluentPut("productVariant", new JSONObject(true)
                        .fluentPut("id", variantId)
                        .fluentPut("traits", new JSONObject(true).fluentPut("size", "9"))
                        .fluentPut("sizeChart", new JSONObject(true).fluentPut("displayOptions", List.of(
                                new JSONObject(true).fluentPut("size", "EU " + euSize))))
                        .fluentPut("product", new JSONObject(true)
                                .fluentPut("title", "Product " + id)
                                .fluentPut("styleId", styleId)));
    }

    private static JSONObject cursorPage(boolean hasNextPage, String endCursor, JSONObject... nodes) {
        return page(new JSONObject(true)
                .fluentPut("hasNextPage", hasNextPage)
                .fluentPut("endCursor", endCursor), nodes);
    }

    private static JSONObject page(JSONObject pageInfo, JSONObject... nodes) {
        JSONArray edges = new JSONArray();
        for (JSONObject node : nodes) {
            edges.add(new JSONObject(true).fluentPut("node", node));
        }
        return new JSONObject(true).fluentPut("edges", edges).fluentPut("pageInfo", pageInfo);
    }

    private static class FakeStockXClient extends StockXClient {
        private final Deque<JSONObject> pendingPages = new ArrayDeque<>();

        @Override
        public JSONObject queryPendingAsks(String after, StockXAccount account) {
            return pendingPages.removeFirst();
        }
    }

    private static class FakePriceManager extends PriceManager {
        private final Map<String, Integer> prices;
        private final Set<String> refreshedStyles = new LinkedHashSet<>();

        FakePriceManager(Map<String, Integer> prices) {
            this.prices = prices;
        }

        @Override
        public void refreshPrices(Set<String> modelNos) {
            refreshedStyles.addAll(modelNos);
        }

        @Override
        public Integer getPoisonPrice(String modelNo, String euSize) {
            return prices.get(modelNo + ":" + euSize);
        }
    }

    private static class FakeStockXService extends StockXService {
        private final List<StockXListingCreateItem> submitted = new ArrayList<>();
        private final List<Integer> submissionBatchSizes = new ArrayList<>();

        @Override
        public void submitTaskListings(Long taskId, List<StockXListingCreateItem> items,
                                       Map<String, Long> variantToTaskItemId, StockXAccount account) {
            submissionBatchSizes.add(items.size());
            submitted.addAll(items);
        }
    }

    private static class InMemoryRecorder implements StockXReplenishmentTaskRecorder {
        private TaskDO task;
        private final List<TaskItemDO> items = new ArrayList<>();
        private long nextItemId = 1;
        private boolean completed;

        @Override
        public Long start(TaskDO task) {
            task.setId(88L);
            this.task = task;
            return task.getId();
        }

        @Override
        public void record(TaskItemDO item) {
            item.setId(nextItemId++);
            items.add(item);
        }

        @Override
        public void updateProgress(Long taskId, int pageCount, String attributes) {
        }

        @Override
        public void complete(Long taskId, String cost, String summary) {
            completed = true;
        }

        @Override
        public void fail(Long taskId, String cost, String reason) {
        }
    }
}
