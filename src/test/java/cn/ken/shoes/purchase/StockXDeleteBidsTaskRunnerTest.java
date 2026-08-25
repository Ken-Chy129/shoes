package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidDeleteItem;
import cn.ken.shoes.model.stockx.StockXBidDeleteResult;
import cn.ken.shoes.task.StockXDeleteBidsTaskRunner;
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

class StockXDeleteBidsTaskRunnerTest {

    @Test
    void deletesFirstPageInBatchesAndConfirmsZeroThreeTimes() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(IntStreamBids.create(12)));
        client.pages.add(emptyPage());
        client.pages.add(emptyPage());
        client.pages.add(emptyPage());
        List<TaskItemDO> stored = new ArrayList<>();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        AtomicInteger rounds = new AtomicInteger();

        runner(201L, client, taskMapper(status, attributes, rounds), itemMapper(stored)).run();

        assertThat(client.queryCursors).containsExactly(null, null, null, null);
        assertThat(client.deletedBatches).extracting(List::size).containsExactly(10, 2);
        assertThat(stored).hasSize(12)
                .allSatisfy(item -> {
                    assertThat(item.getOrderStatus()).isEqualTo("已撤销");
                    assertThat(item.getOperateResult()).isEqualTo("撤销成功");
                });
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get())
                .contains("\"operation\":\"delete_bids\"")
                .contains("\"stage\":\"已完成\"")
                .contains("\"deleted\":12")
                .contains("\"remaining\":0");
        assertThat(rounds.get()).isEqualTo(1);
    }

    @Test
    void retriesABidThatRemainsVisibleAfterARejectedDelete() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(List.of(bid("bid-1"))));
        client.pages.add(page(List.of(bid("bid-1"))));
        client.pages.add(emptyPage());
        client.pages.add(emptyPage());
        client.pages.add(emptyPage());
        client.deleteResults.add(List.of(new StockXBidDeleteResult("bid-1", "FAILED", false)));
        client.deleteResults.add(List.of(new StockXBidDeleteResult(
                "bid-1", "Bid bid-1 deleted successfully", true)));
        List<TaskItemDO> stored = new ArrayList<>();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();

        runner(202L, client, taskMapper(status, attributes, new AtomicInteger()), itemMapper(stored)).run();

        assertThat(client.deletedBatches).hasSize(2);
        assertThat(stored).extracting(TaskItemDO::getOrderStatus)
                .containsExactly("撤销失败", "已撤销");
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(attributes.get()).contains("\"deleted\":1");
    }

    @Test
    void failsAfterThreeRoundsWithoutAnySuccessfulDeletion() {
        FakeStockXClient client = new FakeStockXClient();
        client.pages.add(page(List.of(bid("stuck-bid"))));
        client.pages.add(page(List.of(bid("stuck-bid"))));
        client.pages.add(page(List.of(bid("stuck-bid"))));
        client.deleteResults.add(failed("stuck-bid"));
        client.deleteResults.add(failed("stuck-bid"));
        client.deleteResults.add(failed("stuck-bid"));
        AtomicReference<String> failure = new AtomicReference<>();
        TaskMapper mapper = proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskFailed")) failure.set((String) args[1]);
            return null;
        });

        runner(203L, client, mapper, itemMapper(new ArrayList<>())).run();

        assertThat(client.deletedBatches).hasSize(3);
        assertThat(failure.get()).contains("连续3轮").contains("没有成功撤销");
    }

    @Test
    void honorsPurchaseTaskCancellationBeforeReadingAnyBids() {
        FakeStockXClient client = new FakeStockXClient();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicReference<String> attributes = new AtomicReference<>();
        TaskSwitch.cancelPurchase(account().getName());

        try {
            runner(204L, client, taskMapper(status, attributes, new AtomicInteger()),
                    itemMapper(new ArrayList<>())).run();

            assertThat(client.queryCursors).isEmpty();
            assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.CANCEL.getCode());
            assertThat(attributes.get()).contains("\"stage\":\"已取消\"");
        } finally {
            TaskSwitch.clearPurchaseState(account().getName());
        }
    }

    @Test
    void retriesATransientFailureWhenReadingTheCurrentBidPage() {
        FakeStockXClient client = new FakeStockXClient();
        client.transientNullReads = 1;
        client.pages.add(emptyPage());
        client.pages.add(emptyPage());
        client.pages.add(emptyPage());
        AtomicReference<String> status = new AtomicReference<>();

        runner(205L, client, taskMapper(status, new AtomicReference<>(), new AtomicInteger()),
                itemMapper(new ArrayList<>())).run();

        assertThat(client.queryCursors).hasSize(4);
        assertThat(status.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
    }

    private static List<StockXBidDeleteResult> failed(String id) {
        return List.of(new StockXBidDeleteResult(id, "FAILED", false));
    }

    private static StockXDeleteBidsTaskRunner runner(Long taskId, StockXClient client,
                                                      TaskMapper taskMapper,
                                                      TaskItemMapper itemMapper) {
        return new StockXDeleteBidsTaskRunner(account(), taskId, client, taskMapper, itemMapper) {
            @Override
            protected void waitBeforeNextCheck(long delayMs) {
                // 测试不真实等待。
            }
        };
    }

    private static TaskMapper taskMapper(AtomicReference<String> status,
                                         AtomicReference<String> attributes,
                                         AtomicInteger rounds) {
        return proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("updateTaskStatus")) status.set((String) args[1]);
            if (method.equals("updateTaskAttributes")) attributes.set((String) args[1]);
            if (method.equals("updateTaskRound")) rounds.set((Integer) args[1]);
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

    private static JSONObject page(List<JSONObject> bids) {
        JSONArray edges = new JSONArray();
        bids.forEach(bid -> edges.add(new JSONObject(true).fluentPut("node", bid)));
        return new JSONObject(true)
                .fluentPut("edges", edges)
                .fluentPut("totalCount", bids.size())
                .fluentPut("pageInfo", new JSONObject(true)
                        .fluentPut("hasNextPage", false)
                        .fluentPut("endCursor", null));
    }

    private static JSONObject emptyPage() {
        return page(List.of());
    }

    private static JSONObject bid(String id) {
        return new JSONObject(true)
                .fluentPut("id", id)
                .fluentPut("amount", 100)
                .fluentPut("currencyCode", "USD")
                .fluentPut("creationDate", "2026-08-25T01:02:03Z")
                .fluentPut("productVariant", new JSONObject(true)
                        .fluentPut("id", "variant-" + id)
                        .fluentPut("traits", new JSONObject(true).fluentPut("size", "9"))
                        .fluentPut("product", new JSONObject(true)
                                .fluentPut("title", id)
                                .fluentPut("styleId", "STYLE-" + id)));
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("delete-bids-account");
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
        private final Deque<List<StockXBidDeleteResult>> deleteResults = new ArrayDeque<>();
        private final List<String> queryCursors = new ArrayList<>();
        private final List<List<StockXBidDeleteItem>> deletedBatches = new ArrayList<>();
        private int transientNullReads;

        @Override
        public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                            StockXAccount account) {
            assertThat(operation).isEqualTo(StockXPurchaseOperation.BIDS);
            queryCursors.add(after);
            if (transientNullReads > 0) {
                transientNullReads--;
                return null;
            }
            return pages.removeFirst();
        }

        @Override
        public List<StockXBidDeleteResult> deleteBids(List<StockXBidDeleteItem> items,
                                                      StockXAccount account) {
            deletedBatches.add(List.copyOf(items));
            if (!deleteResults.isEmpty()) {
                return deleteResults.removeFirst();
            }
            return items.stream().map(item -> new StockXBidDeleteResult(item.chainId(),
                    "Bid " + item.chainId() + " deleted successfully", true)).toList();
        }
    }

    private static final class IntStreamBids {
        private static List<JSONObject> create(int count) {
            return java.util.stream.IntStream.rangeClosed(1, count)
                    .mapToObj(i -> bid("bid-" + i))
                    .toList();
        }
    }
}
