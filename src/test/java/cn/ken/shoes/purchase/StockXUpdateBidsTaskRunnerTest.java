package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXBidUpdateInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidBatch;
import cn.ken.shoes.model.stockx.StockXBidUpdateItem;
import cn.ken.shoes.task.StockXUpdateBidsTaskRunner;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockXUpdateBidsTaskRunnerTest {

    @Test
    void resolvesActiveBidMetadataAndSubmitsUpdatesInBatchesOfFifty() {
        FakeStockXClient client = new FakeStockXClient();
        List<JSONObject> edges = new ArrayList<>();
        List<StockXBidUpdateInputExcel> input = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            JSONObject node = activeBid("bid-" + i, "variant-" + i);
            if (i == 1) {
                node.put("deliveryOptionType", "FLEX");
                node.put("currencyCode", "USD");
                node.put("checkoutType", "STANDARD");
            }
            edges.add(new JSONObject(true).fluentPut("node", node));
            input.add(input("bid-" + i, String.valueOf(100 + i)));
        }
        client.activeBids = page(edges);
        List<TaskItemDO> stored = new ArrayList<>();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();

        new StockXUpdateBidsTaskRunner(account(), 501L, input, client,
                taskMapper(status, attributes), itemMapper(stored)).run();

        assertThat(client.submitted).hasSize(2);
        assertThat(client.submitted.get(0)).hasSize(50);
        assertThat(client.submitted.get(1)).hasSize(1);
        assertThat(client.submitted.get(0).get(0)).satisfies(item -> {
            assertThat(item.id()).isEqualTo("bid-1");
            assertThat(item.amount()).isEqualByComparingTo("101");
            assertThat(item.deliveryOptionType()).isEqualTo("FLEX");
            assertThat(item.currency()).isEqualTo("USD");
            assertThat(item.checkoutType()).isEqualTo("STANDARD");
        });
        assertThat(client.submitted.get(0).get(1).deliveryOptionType()).isEqualTo("HOME_DELIVERY");
        assertThat(stored).hasSize(51).allSatisfy(item -> {
            assertThat(item.getOperateResult()).isEqualTo("修改出价已提交");
            assertThat(item.getOrderNumber()).startsWith("update-batch-");
        });
        assertThat(stored.get(0).getListingId()).isEqualTo("bid-1");
        assertThat(stored.get(0).getProductId()).isEqualTo("variant-1");
        assertThat(stored.get(0).getCurrentPrice()).isEqualByComparingTo("101");
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get()).contains("\"operation\":\"update_bids\"")
                .contains("\"submitted\":51").contains("\"failed\":0");
    }

    @Test
    void recordsMissingBidIdsWithoutCallingTheMutation() {
        FakeStockXClient client = new FakeStockXClient();
        List<TaskItemDO> stored = new ArrayList<>();

        new StockXUpdateBidsTaskRunner(account(), 502L, List.of(input("stale-bid", "88")), client,
                taskMapper(new AtomicReference<>(), new AtomicReference<>()), itemMapper(stored)).run();

        assertThat(client.submitted).isEmpty();
        assertThat(stored).singleElement().satisfies(item -> {
            assertThat(item.getListingId()).isEqualTo("stale-bid");
            assertThat(item.getOperateResult()).isEqualTo("修改出价失败-未找到当前有效出价ID");
        });
    }

    private static StockXBidUpdateInputExcel input(String bidId, String price) {
        StockXBidUpdateInputExcel row = new StockXBidUpdateInputExcel();
        row.setBidId(bidId);
        row.setPrice(new BigDecimal(price));
        return row;
    }

    private static JSONObject activeBid(String bidId, String variantId) {
        return new JSONObject(true)
                .fluentPut("id", bidId)
                .fluentPut("amount", "90")
                .fluentPut("currencyCode", "USD")
                .fluentPut("productVariant", new JSONObject(true)
                        .fluentPut("id", variantId)
                        .fluentPut("traits", new JSONObject(true).fluentPut("size", "9"))
                        .fluentPut("product", new JSONObject(true)
                                .fluentPut("title", "Product " + bidId)
                                .fluentPut("styleId", "STYLE-" + bidId)));
    }

    private static JSONObject page(List<JSONObject> edges) {
        JSONArray array = new JSONArray();
        array.addAll(edges);
        return new JSONObject(true).fluentPut("edges", array)
                .fluentPut("pageInfo", new JSONObject(true).fluentPut("hasNextPage", false));
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("update-bids-account");
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
        private JSONObject activeBids = page(List.of());
        private final List<List<StockXBidUpdateItem>> submitted = new ArrayList<>();

        @Override
        public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                            StockXAccount account) {
            return activeBids;
        }

        @Override
        public StockXBidBatch updateBids(List<StockXBidUpdateItem> items, StockXAccount account) {
            submitted.add(List.copyOf(items));
            return new StockXBidBatch("update-batch-" + submitted.size(), "QUEUED");
        }
    }
}
