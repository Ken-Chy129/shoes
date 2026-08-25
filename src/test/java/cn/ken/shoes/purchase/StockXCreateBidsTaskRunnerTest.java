package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidBatch;
import cn.ken.shoes.model.stockx.StockXBidCreateItem;
import cn.ken.shoes.task.StockXCreateBidsTaskRunner;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockXCreateBidsTaskRunnerTest {

    @Test
    void resolvesExcelRowsAndSubmitsObservedCreateBidsPayload() {
        FakeStockXClient client = new FakeStockXClient();
        client.searchRows = List.of(priceRow("variant-1", "100289469", "4.5", "6", "37.5"));
        List<TaskItemDO> stored = new ArrayList<>();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();

        StockXCreateBidsTaskRunner runner = new StockXCreateBidsTaskRunner(
                account(), 201L, List.of(input("100289469", "US M 4.5", "1")), client,
                taskMapper(status, attributes), itemMapper(stored));

        runner.run();

        assertThat(client.submitted).singleElement().satisfies(batch ->
                assertThat(batch).singleElement().satisfies(item -> {
                    assertThat(item.variantId()).isEqualTo("variant-1");
                    assertThat(item.amount()).isEqualByComparingTo("1");
                    assertThat(item.localizedSizeType()).isEqualTo("us m");
                }));
        assertThat(stored).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo("variant-1");
            assertThat(item.getStyleId()).isEqualTo("100289469");
            assertThat(item.getSize()).isEqualTo("4.5");
            assertThat(item.getEuSize()).isEqualTo("37.5");
            assertThat(item.getCurrentPrice()).isEqualByComparingTo("1");
            assertThat(item.getListingId()).isEqualTo("batch-1");
            assertThat(item.getOrderStatus()).isEqualTo("QUEUED");
            assertThat(item.getOperateResult()).isEqualTo("出价已提交");
        });
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get())
                .contains("\"operation\":\"create_bids\"")
                .contains("\"submitted\":1")
                .contains("\"skipped\":0")
                .contains("\"failed\":0");
    }

    @Test
    void skipsVariantsThatAlreadyHaveAnActiveBid() {
        FakeStockXClient client = new FakeStockXClient();
        client.activeBids = activeBidsPage("variant-1");
        client.searchRows = List.of(priceRow("variant-1", "STYLE-1", "9", null, "42"));
        List<TaskItemDO> stored = new ArrayList<>();

        new StockXCreateBidsTaskRunner(account(), 202L,
                List.of(input("STYLE-1", "9", "80")), client,
                taskMapper(new AtomicReference<>(), new AtomicReference<>()), itemMapper(stored)).run();

        assertThat(client.submitted).isEmpty();
        assertThat(stored).singleElement().satisfies(item ->
                assertThat(item.getOperateResult()).isEqualTo("跳过-已有有效出价"));
    }

    @Test
    void storesAVisibleFailureWhenTheRequestedVariantCannotBeFound() {
        FakeStockXClient client = new FakeStockXClient();
        List<TaskItemDO> stored = new ArrayList<>();

        new StockXCreateBidsTaskRunner(account(), 203L,
                List.of(input("MISSING", "EU 42", "75")), client,
                taskMapper(new AtomicReference<>(), new AtomicReference<>()), itemMapper(stored)).run();

        assertThat(client.submitted).isEmpty();
        assertThat(stored).singleElement().satisfies(item -> {
            assertThat(item.getStyleId()).isEqualTo("MISSING");
            assertThat(item.getOperateResult()).isEqualTo("出价失败-未找到对应货号尺码");
        });
    }

    @Test
    void resolvesDistinctModelsWithBoundedConcurrencyAndPublishesProgress() throws Exception {
        FakeStockXClient client = new FakeStockXClient();
        client.blockSearches(2);
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        List<TaskItemDO> stored = Collections.synchronizedList(new ArrayList<>());
        StockXCreateBidsTaskRunner runner = new StockXCreateBidsTaskRunner(
                account(), 204L,
                List.of(input("STYLE-1", "9", "80"), input("STYLE-2", "9", "81"),
                        input("STYLE-3", "9", "82")),
                client, taskMapper(status, attributes), itemMapper(stored), 2);

        Thread taskThread = Thread.startVirtualThread(runner);
        assertThat(client.searchesStarted.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(client.maxConcurrentSearches.get()).isEqualTo(2);
        assertThat(attributes.get())
                .contains("\"stage\":\"预处理\"")
                .contains("\"modelTotal\":3")
                .contains("\"modelsResolved\":0");

        client.releaseSearches.countDown();
        taskThread.join(2_000);
        assertThat(taskThread.isAlive()).isFalse();
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get())
                .contains("\"stage\":\"已完成\"")
                .contains("\"processed\":3")
                .contains("\"modelsResolved\":3")
                .contains("\"submitted\":3");
    }

    @Test
    void cancelsWhileParallelModelSearchesAreStillWaiting() throws Exception {
        FakeStockXClient client = new FakeStockXClient();
        client.blockSearches(2);
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        StockXCreateBidsTaskRunner runner = new StockXCreateBidsTaskRunner(
                account(), 205L,
                List.of(input("STYLE-1", "9", "80"), input("STYLE-2", "9", "81")),
                client, taskMapper(status, attributes), itemMapper(new ArrayList<>()), 2);
        Thread taskThread = Thread.startVirtualThread(runner);
        assertThat(client.searchesStarted.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            TaskSwitch.cancelPurchase(account().getName());
            taskThread.join(2_000);

            assertThat(taskThread.isAlive()).isFalse();
            assertThat(client.searchInterrupted.get()).isTrue();
            assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.CANCEL.getCode());
            assertThat(attributes.get()).contains("\"stage\":\"已取消\"");
        } finally {
            client.releaseSearches.countDown();
            TaskSwitch.clearPurchaseState(account().getName());
        }
    }

    @Test
    void searchesEachNormalizedModelOnlyOnceAcrossMultipleSizes() {
        FakeStockXClient client = new FakeStockXClient();
        client.searchRows = List.of(
                priceRow("variant-9", "STYLE-1", "9", null, "42"),
                priceRow("variant-10", "STYLE-1", "10", null, "44"));

        new StockXCreateBidsTaskRunner(account(), 206L,
                List.of(input("style-1", "9", "80"), input("STYLE-1", "10", "81")), client,
                taskMapper(new AtomicReference<>(), new AtomicReference<>()), itemMapper(new ArrayList<>())).run();

        assertThat(client.searchCalls.get()).isEqualTo(1);
        assertThat(client.submitted).singleElement().satisfies(batch -> assertThat(batch).hasSize(2));
    }

    @Test
    void stopsBeforeSubmittingWhenPendingTaskItemHasNoDatabaseId() {
        FakeStockXClient client = new FakeStockXClient();
        client.searchRows = List.of(priceRow("variant-1", "STYLE-1", "9", null, "42"));
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        TaskItemMapper mapperWithoutGeneratedId = proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) return 1;
            if (method.equals("updateById")) return 0;
            return null;
        });

        new StockXCreateBidsTaskRunner(account(), 207L,
                List.of(input("STYLE-1", "9", "80")), client,
                taskMapper(status, attributes), mapperWithoutGeneratedId).run();

        assertThat(client.submitted).isEmpty();
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.FAILED.getCode());
        assertThat(attributes.get()).contains("\"stage\":\"执行失败\"");
    }

    @Test
    void reportsFailureWhenSubmittedTaskItemCannotBeUpdated() {
        FakeStockXClient client = new FakeStockXClient();
        client.searchRows = List.of(priceRow("variant-1", "STYLE-1", "9", null, "42"));
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        TaskItemMapper mapperWithFailedUpdate = proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                ((TaskItemDO) args[0]).setId(2_001L);
                return 1;
            }
            if (method.equals("updateById")) return 0;
            return null;
        });

        new StockXCreateBidsTaskRunner(account(), 208L,
                List.of(input("STYLE-1", "9", "80")), client,
                taskMapper(status, attributes), mapperWithFailedUpdate).run();

        assertThat(client.submitted).singleElement().satisfies(batch -> assertThat(batch).hasSize(1));
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.FAILED.getCode());
        assertThat(attributes.get())
                .contains("\"stage\":\"执行失败\"")
                .contains("\"pending\":0")
                .contains("\"submitted\":1");
    }

    @Test
    void submitsPreparedBidsInBatchesOfOneHundred() {
        FakeStockXClient client = new FakeStockXClient();
        client.generateSearchRows = true;
        List<StockXBidInputExcel> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(input("STYLE-" + i, "9", "80"));
        }

        new StockXCreateBidsTaskRunner(account(), 209L, rows, client,
                taskMapper(new AtomicReference<>(), new AtomicReference<>()),
                itemMapper(new ArrayList<>())).run();

        assertThat(client.submitted).extracting(List::size).containsExactly(100, 1);
    }

    @Test
    void derivesStockXLocalizedSizeTypeFromExcelSize() {
        assertThat(StockXCreateBidsTaskRunner.localizedSizeType("4.5")).isEqualTo("us m");
        assertThat(StockXCreateBidsTaskRunner.localizedSizeType("US M 4.5")).isEqualTo("us m");
        assertThat(StockXCreateBidsTaskRunner.localizedSizeType("US W 6")).isEqualTo("us w");
        assertThat(StockXCreateBidsTaskRunner.localizedSizeType("6.5 W")).isEqualTo("us w");
        assertThat(StockXCreateBidsTaskRunner.localizedSizeType("EU 42")).isEqualTo("eu");
    }

    private static StockXBidInputExcel input(String styleId, String size, String price) {
        StockXBidInputExcel row = new StockXBidInputExcel();
        row.setStyleId(styleId);
        row.setSize(size);
        row.setPrice(new BigDecimal(price));
        return row;
    }

    private static StockXPriceExcel priceRow(String variantId, String styleId, String usm,
                                             String usw, String eu) {
        StockXPriceExcel row = new StockXPriceExcel();
        row.setId(variantId);
        row.setModelNo(styleId);
        row.setTitle("Test product");
        row.setBrand("Test brand");
        row.setUsmSize(usm);
        row.setUswSize(usw);
        row.setEuSize(eu);
        return row;
    }

    private static JSONObject emptyActiveBidsPage() {
        return new JSONObject(true)
                .fluentPut("edges", new JSONArray())
                .fluentPut("pageInfo", new JSONObject(true).fluentPut("hasNextPage", false));
    }

    private static JSONObject activeBidsPage(String variantId) {
        return new JSONObject(true)
                .fluentPut("edges", new JSONArray(List.of(new JSONObject(true)
                        .fluentPut("node", new JSONObject(true)
                                .fluentPut("productVariant", new JSONObject(true)
                                        .fluentPut("id", variantId))))))
                .fluentPut("pageInfo", new JSONObject(true).fluentPut("hasNextPage", false));
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("purchase-account");
        account.setCountry("US");
        return account;
    }

    private static TaskMapper taskMapper(AtomicReference<String> status,
                                         AtomicReference<String> attributes) {
        return proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskStatus")) status.set((String) args[1]);
            if (method.equals("updateTaskFailed")) status.set(TaskDO.TaskStatusEnum.FAILED.getCode());
            if (method.equals("updateTaskAttributes")) attributes.set((String) args[1]);
            return null;
        });
    }

    private static TaskItemMapper itemMapper(List<TaskItemDO> stored) {
        AtomicLong ids = new AtomicLong(1_000L);
        return proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                TaskItemDO item = (TaskItemDO) args[0];
                item.setId(ids.incrementAndGet());
                stored.add(item);
                return 1;
            }
            if (method.equals("updateById")) {
                TaskItemDO item = (TaskItemDO) args[0];
                assertThat(item.getId()).as("updated task item id").isNotNull();
                return stored.stream().anyMatch(existing -> item.getId().equals(existing.getId())) ? 1 : 0;
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (ignored, method, args) -> handler.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(String method, Object[] args);
    }

    private static class FakeStockXClient extends StockXClient {
        private JSONObject activeBids = emptyActiveBidsPage();
        private List<StockXPriceExcel> searchRows = List.of();
        private final List<List<StockXBidCreateItem>> submitted = new ArrayList<>();
        private final AtomicInteger searchCalls = new AtomicInteger();
        private final AtomicInteger concurrentSearches = new AtomicInteger();
        private final AtomicInteger maxConcurrentSearches = new AtomicInteger();
        private final AtomicBoolean searchInterrupted = new AtomicBoolean();
        private boolean generateSearchRows;
        private boolean searchesBlocked;
        private CountDownLatch searchesStarted = new CountDownLatch(0);
        private CountDownLatch releaseSearches = new CountDownLatch(0);

        private void blockSearches(int count) {
            searchesBlocked = true;
            searchesStarted = new CountDownLatch(count);
            releaseSearches = new CountDownLatch(1);
        }

        @Override
        public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                            StockXAccount account) {
            return activeBids;
        }

        @Override
        public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String searchType,
                                                               String country, StockXAccount account) {
            searchCalls.incrementAndGet();
            int active = concurrentSearches.incrementAndGet();
            maxConcurrentSearches.accumulateAndGet(active, Math::max);
            searchesStarted.countDown();
            try {
                releaseSearches.await();
                if (!searchRows.isEmpty() || !searchesBlocked) {
                    if (generateSearchRows) {
                        return List.of(priceRow("variant-" + modelNo, modelNo, "9", null, "42"));
                    }
                    return searchRows;
                }
                return List.of(priceRow("variant-" + modelNo, modelNo, "9", null, "42"));
            } catch (InterruptedException e) {
                searchInterrupted.set(true);
                Thread.currentThread().interrupt();
                throw new cn.ken.shoes.exception.TaskCancelledException();
            } finally {
                concurrentSearches.decrementAndGet();
            }
        }

        @Override
        public StockXBidBatch createBids(List<StockXBidCreateItem> items, StockXAccount account) {
            submitted.add(List.copyOf(items));
            return new StockXBidBatch("batch-" + submitted.size(), "QUEUED");
        }
    }
}
