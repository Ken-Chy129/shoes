package cn.ken.shoes.order;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.task.StockXFetchOrdersTaskRunner;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockXFetchOrdersTaskRunnerTest {

    @Test
    void fetchesPendingOrdersAcrossCursorsAndAddsPoisonPrices() {
        FakeStockXClient client = new FakeStockXClient();
        client.pendingPages.add(page(true, "cursor-1",
                pendingAsk("ask-1", "order-1", "STYLE-1", "42", false)));
        client.pendingPages.add(page(false, null,
                pendingAsk("ask-2", "order-2", "STYLE-2", "43", true)));
        FakePriceManager priceManager = new FakePriceManager();
        List<TaskItemDO> recordedItems = new ArrayList<>();
        AtomicReference<String> finalStatus = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                recordedItems.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskStatus")) {
                finalStatus.set((String) args[1]);
            } else if (method.equals("updateTaskAttributes")) {
                attributes.set((String) args[1]);
            }
            return null;
        });

        StockXFetchOrdersTaskRunner runner = new StockXFetchOrdersTaskRunner(
                account(), 88L, List.of(StockXOrderCategory.PENDING),
                client, priceManager, taskMapper, taskItemMapper);

        runner.run();

        assertThat(client.requestedCursors).containsExactly(null, "cursor-1");
        assertThat(priceManager.loadedStyles).containsExactlyInAnyOrder("STYLE-1", "STYLE-2");
        assertThat(recordedItems).hasSize(2);
        assertThat(recordedItems).extracting(TaskItemDO::getOperateResult)
                .containsExactly("未延期", "已延期");
        assertThat(recordedItems).extracting(TaskItemDO::getPoisonPrice)
                .containsExactly(new BigDecimal("1001"), new BigDecimal("1002"));
        assertThat(finalStatus.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get()).contains("\"pending\":2").doesNotContain("fetchPayout");
    }

    @Test
    void historicalOrdersDoNotQueryPoisonPrices() {
        FakeStockXClient client = new FakeStockXClient();
        client.historicalPages.add(page(false, null,
                historicalOrder("listing-1", "order-1", "STYLE-HISTORY", "45 1/3")));
        FakePriceManager priceManager = new FakePriceManager();
        List<TaskItemDO> recordedItems = new ArrayList<>();
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                recordedItems.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> null);

        StockXFetchOrdersTaskRunner runner = new StockXFetchOrdersTaskRunner(
                account(), 89L, List.of(StockXOrderCategory.CANCELLED),
                client, priceManager, taskMapper, taskItemMapper);

        runner.run();

        assertThat(recordedItems).hasSize(1);
        assertThat(recordedItems.getFirst().getPoisonPrice()).isNull();
        assertThat(priceManager.loadedStyles).isEmpty();
        assertThat(priceManager.priceLookups).isEmpty();
        assertThat(client.payoutRequests).isEmpty();
    }

    @Test
    void completedOrdersFetchFinalPayoutWithoutQueryingPoisonPrices() {
        FakeStockXClient client = new FakeStockXClient();
        client.historicalPages.add(page(false, null,
                historicalOrder("listing-completed", "order-completed", "STYLE-COMPLETED", "43")));
        client.payouts = Map.of("listing-completed", new BigDecimal("100.68"));
        FakePriceManager priceManager = new FakePriceManager();
        List<TaskItemDO> recordedItems = new ArrayList<>();
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                recordedItems.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> null);

        StockXFetchOrdersTaskRunner runner = new StockXFetchOrdersTaskRunner(
                account(), 90L, List.of(StockXOrderCategory.COMPLETED),
                client, priceManager, taskMapper, taskItemMapper);

        runner.run();

        assertThat(client.payoutRequests).containsExactly("listing-completed");
        assertThat(recordedItems).hasSize(1);
        assertThat(recordedItems.getFirst().getPayoutAmount())
                .isEqualByComparingTo(new BigDecimal("100.68"));
        assertThat(recordedItems.getFirst().getPoisonPrice()).isNull();
        assertThat(priceManager.loadedStyles).isEmpty();
        assertThat(priceManager.priceLookups).isEmpty();
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        account.setCountry("US");
        account.setEnabled(true);
        return account;
    }

    private static JSONObject pendingAsk(String askId, String orderNumber, String styleId,
                                         String euSize, boolean extended) {
        return new JSONObject(true)
                .fluentPut("id", askId)
                .fluentPut("orderNumber", orderNumber)
                .fluentPut("amount", 200)
                .fluentPut("currentCurrency", "USD")
                .fluentPut("soldOn", "2026-07-16T03:04:05.000Z")
                .fluentPut("dateToShipBy", "2026-07-20T23:59:59.000Z")
                .fluentPut("shippingExtensionRequested", extended)
                .fluentPut("productVariant", new JSONObject(true)
                        .fluentPut("id", "variant-" + askId)
                        .fluentPut("traits", new JSONObject(true).fluentPut("size", "9"))
                        .fluentPut("sizeChart", new JSONObject(true).fluentPut("displayOptions", List.of(
                                new JSONObject(true).fluentPut("size", "EU " + euSize))))
                        .fluentPut("product", new JSONObject(true)
                                .fluentPut("title", "Product " + askId)
                                .fluentPut("styleId", styleId)));
    }

    private static JSONObject historicalOrder(String listingId, String orderNumber, String styleId,
                                              String euSize) {
        return new JSONObject(true)
                .fluentPut("id", listingId)
                .fluentPut("amount", 300)
                .fluentPut("currency", "USD")
                .fluentPut("soldOn", "2026-07-16T03:04:05.000Z")
                .fluentPut("associatedOrders", new JSONObject(true)
                        .fluentPut("standardizedSellOrder", new JSONObject(true)
                                .fluentPut("orderNumber", orderNumber)))
                .fluentPut("productVariant", new JSONObject(true)
                        .fluentPut("id", "variant-" + listingId)
                        .fluentPut("traits", new JSONObject(true).fluentPut("size", "11"))
                        .fluentPut("sizeChart", new JSONObject(true).fluentPut("displayOptions", List.of(
                                new JSONObject(true).fluentPut("size", "EU " + euSize))))
                        .fluentPut("product", new JSONObject(true)
                                .fluentPut("title", "Product " + listingId)
                                .fluentPut("styleId", styleId)));
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

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object result = invocation.call(method.getName(), args == null ? new Object[0] : args);
                    if (result != null || method.getReturnType() == void.class) {
                        return result;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args);
    }

    private static class FakeStockXClient extends StockXClient {
        private final Deque<JSONObject> pendingPages = new ArrayDeque<>();
        private final Deque<JSONObject> historicalPages = new ArrayDeque<>();
        private final List<String> requestedCursors = new ArrayList<>();
        private final List<String> payoutRequests = new ArrayList<>();
        private Map<String, BigDecimal> payouts = Map.of();

        @Override
        public JSONObject queryPendingAsks(String after, StockXAccount account) {
            requestedCursors.add(after);
            return pendingPages.removeFirst();
        }

        @Override
        public JSONObject queryOrderListings(StockXOrderCategory category, int pageNumber, StockXAccount account) {
            if (historicalPages.isEmpty()) {
                throw new AssertionError("待处理订单不应调用SellerListings");
            }
            return historicalPages.removeFirst();
        }

        @Override
        public BigDecimal queryOrderPayout(String listingId, StockXAccount account) {
            payoutRequests.add(listingId);
            return payouts.get(listingId);
        }
    }

    private static class FakePriceManager extends PriceManager {
        private final Set<String> loadedStyles = new LinkedHashSet<>();
        private final List<String> priceLookups = new ArrayList<>();

        @Override
        public void batchLoadPrices(Set<String> modelNos) {
            loadedStyles.addAll(modelNos);
        }

        @Override
        public Integer getPoisonPrice(String modelNo, String euSize) {
            priceLookups.add(modelNo + ":" + euSize);
            return "STYLE-1".equals(modelNo) ? 1001 : 1002;
        }
    }
}
