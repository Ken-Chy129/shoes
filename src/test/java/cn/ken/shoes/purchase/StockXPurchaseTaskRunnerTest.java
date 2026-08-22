package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.task.StockXPurchaseTaskRunner;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockXPurchaseTaskRunnerTest {

    @Test
    void fetchesAllCursorPagesAndStoresTaskItems() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(true, "cursor-1", bid("bid-1", "STYLE-1")));
        client.pages.add(page(false, null, bid("bid-2", "STYLE-2")));
        List<TaskItemDO> stored = new ArrayList<>();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        AtomicInteger round = new AtomicInteger();
        TaskItemMapper itemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                stored.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskStatus")) status.set((String) args[1]);
            if (method.equals("updateTaskAttributes")) attributes.set((String) args[1]);
            if (method.equals("updateTaskRound")) round.set((Integer) args[1]);
            return null;
        });

        StockXPurchaseTaskRunner runner = new StockXPurchaseTaskRunner(
                account(), 99L, StockXPurchaseOperation.BIDS, client, taskMapper, itemMapper) {
            @Override
            protected void waitBeforeRetry(long delayMs) {
                // 测试不真实等待。
            }
        };

        runner.run();

        assertThat(client.cursors).containsExactly(null, "cursor-1");
        assertThat(stored).extracting(TaskItemDO::getListingId).containsExactly("bid-1", "bid-2");
        assertThat(round.get()).isEqualTo(2);
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get()).contains("\"operation\":\"bids\"").contains("\"total\":2");
    }

    @Test
    void failsWhenStockXClaimsAnotherPageWithoutAdvancingCursor() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(true, "same-cursor", bid("bid-1", "STYLE-1")));
        client.pages.add(page(true, "same-cursor", bid("bid-2", "STYLE-2")));
        AtomicReference<String> failureReason = new AtomicReference<>();
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskFailed")) failureReason.set((String) args[1]);
            return null;
        });

        StockXPurchaseTaskRunner runner = new StockXPurchaseTaskRunner(
                account(), 100L, StockXPurchaseOperation.BIDS, client, taskMapper,
                proxy(TaskItemMapper.class, (method, args) -> method.equals("insert") ? 1 : null)) {
            @Override
            protected void waitBeforeRetry(long delayMs) {
                // 测试不真实等待。
            }
        };

        runner.run();

        assertThat(failureReason.get()).contains("分页游标无效");
    }

    @Test
    void failsWhenStockXPaginationReturnsToAnEarlierCursor() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(true, "cursor-1", bid("bid-1", "STYLE-1")));
        client.pages.add(page(true, "cursor-2", bid("bid-2", "STYLE-2")));
        client.pages.add(page(true, "cursor-1", bid("bid-3", "STYLE-3")));
        AtomicReference<String> failureReason = new AtomicReference<>();
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskFailed")) failureReason.set((String) args[1]);
            return null;
        });

        StockXPurchaseTaskRunner runner = new StockXPurchaseTaskRunner(
                account(), 101L, StockXPurchaseOperation.BIDS, client, taskMapper,
                proxy(TaskItemMapper.class, (method, args) -> method.equals("insert") ? 1 : null)) {
            @Override
            protected void waitBeforeRetry(long delayMs) {
                // 测试不真实等待。
            }
        };

        runner.run();

        assertThat(client.cursors).containsExactly(null, "cursor-1", "cursor-2");
        assertThat(failureReason.get()).contains("分页游标无效");
    }

    private static JSONObject page(boolean hasNextPage, String endCursor, JSONObject... nodes) {
        JSONArray edges = new JSONArray();
        for (JSONObject node : nodes) {
            edges.add(new JSONObject().fluentPut("node", node));
        }
        return new JSONObject()
                .fluentPut("edges", edges)
                .fluentPut("pageInfo", new JSONObject()
                        .fluentPut("hasNextPage", hasNextPage)
                        .fluentPut("endCursor", endCursor));
    }

    private static JSONObject bid(String id, String styleId) {
        return new JSONObject()
                .fluentPut("id", id)
                .fluentPut("amount", 100)
                .fluentPut("currencyCode", "USD")
                .fluentPut("creationDate", "2026-08-22T01:02:03Z")
                .fluentPut("productVariant", new JSONObject()
                        .fluentPut("id", "variant-" + id)
                        .fluentPut("traits", new JSONObject().fluentPut("size", "9"))
                        .fluentPut("product", new JSONObject()
                                .fluentPut("title", styleId)
                                .fluentPut("styleId", styleId)));
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("purchase-account");
        account.setCountry("US");
        return account;
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
        private final Deque<JSONObject> pages = new ArrayDeque<>();
        private final List<String> cursors = new ArrayList<>();

        @Override
        public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                            StockXAccount account) {
            cursors.add(after);
            return pages.removeFirst();
        }
    }
}
