package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
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
import java.util.List;
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
            if (method.equals("updateTaskAttributes")) attributes.set((String) args[1]);
            return null;
        });
    }

    private static TaskItemMapper itemMapper(List<TaskItemDO> stored) {
        return proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                stored.add((TaskItemDO) args[0]);
                return 1;
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

        @Override
        public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                            StockXAccount account) {
            return activeBids;
        }

        @Override
        public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String searchType,
                                                               String country, StockXAccount account) {
            return searchRows;
        }

        @Override
        public StockXBidBatch createBids(List<StockXBidCreateItem> items, StockXAccount account) {
            submitted.add(List.copyOf(items));
            return new StockXBidBatch("batch-" + submitted.size(), "QUEUED");
        }
    }
}
