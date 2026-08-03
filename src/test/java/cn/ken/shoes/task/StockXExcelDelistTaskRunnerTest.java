package cn.ken.shoes.task;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.DelistMode;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StockXExcelDelistTaskRunnerTest {

    @Test
    void styleAndSizeRuleDelistsEveryCurrentlyListedMatchWithoutFullFetch() {
        StockXDelistInputExcel rule = new StockXDelistInputExcel();
        rule.setStyleId("DZ5485-612");
        rule.setSize("US 9.5");
        StockXAccount account = new StockXAccount();
        account.setName("delist-style-size-account");
        AtomicReference<List<String>> submittedIds = new AtomicReference<>();
        AtomicInteger styleSearchCalls = new AtomicInteger();
        StockXClient client = new StockXClient() {
            @Override
            public com.alibaba.fastjson.JSONObject querySellingItemsByInventoryType(
                    String inventoryType, Integer pageNumber, StockXAccount ignored) {
                throw new AssertionError("货号+尺码下架不应全量拉取挂单");
            }

            @Override
            public com.alibaba.fastjson.JSONObject querySellingItemsByStyleId(
                    String inventoryType, Integer pageNumber, String styleId, StockXAccount ignored) {
                styleSearchCalls.incrementAndGet();
                List<com.alibaba.fastjson.JSONObject> items = new ArrayList<>();
                int start = pageNumber == 1 ? 0 : 6;
                int end = pageNumber == 1 ? 6 : 10;
                IntStream.range(start, end).forEach(index -> items.add(listing(
                        "matching-" + index, "DZ5485-612", "9.5", "42.5")));
                if (pageNumber == 2) {
                    items.add(listing("wrong-size", "DZ5485-612", "10", "43"));
                    items.add(listing("fuzzy-style", "DZ5485-621", "9.5", "42.5"));
                }
                com.alibaba.fastjson.JSONArray itemArray = new com.alibaba.fastjson.JSONArray();
                itemArray.addAll(items);
                return new com.alibaba.fastjson.JSONObject(true)
                        .fluentPut("hasMore", pageNumber == 1)
                        .fluentPut("items", itemArray);
            }

            @Override
            public String deleteItems(List<String> idList, StockXAccount ignored) {
                submittedIds.set(List.copyOf(idList));
                return "batch-style-size";
            }

            @Override
            public java.util.Map<String, String> verifyDeleteBatch(
                    String batchId, List<String> listingIds, StockXAccount ignored,
                    Supplier<Boolean> cancelled) {
                return listingIds.stream().collect(java.util.stream.Collectors.toMap(
                        id -> id, id -> "下架成功"));
            }
        };
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> null);
        AtomicInteger itemId = new AtomicInteger();
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                ((cn.ken.shoes.model.entity.TaskItemDO) args[0]).setId((long) itemId.incrementAndGet());
                return 1;
            }
            if ("countSuccessfulDelistsByTaskId".equals(method)) return 10L;
            return null;
        });
        TaskInputSnapshotStore snapshotStore = mock(TaskInputSnapshotStore.class);

        new StockXExcelDelistTaskRunner(account, 90L, "STANDARD", DelistMode.EXCEL, client,
                taskMapper, taskItemMapper, snapshotStore, 0, List.of(rule)).run();

        assertThat(styleSearchCalls).hasValue(2);
        assertThat(submittedIds.get()).containsExactlyElementsOf(
                IntStream.range(0, 10).mapToObj(index -> "matching-" + index).toList());
        verify(snapshotStore).saveDelist(eq(90L), argThat(items ->
                items.size() == 10 && items.stream().allMatch(item -> item.getListingId() != null)));
    }

    @Test
    void normalizesExcelUsEuAndUnicodeFractionSizeNotation() {
        assertThat(StockXExcelDelistTaskRunner.normalizeSize("US 9.5")).isEqualTo("9.5");
        assertThat(StockXExcelDelistTaskRunner.normalizeSize("EU 42")).isEqualTo("42");
        assertThat(StockXExcelDelistTaskRunner.normalizeSize("42⅔")).isEqualTo("42.5");
    }

    @Test
    void resumeStartsAfterAlreadyCompletedBatches() {
        String accountName = "delist-resume-account";
        List<StockXDelistInputExcel> input = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            StockXDelistInputExcel item = new StockXDelistInputExcel();
            item.setListingId("listing-" + i);
            input.add(item);
        }
        ShoesContext.loadDelistExcel(accountName, "STANDARD", input);

        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        AtomicInteger insertedItems = new AtomicInteger();
        AtomicReference<String> finalStatus = new AtomicReference<>();
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if ("updateTaskStatus".equals(method)) finalStatus.set((String) args[1]);
            return null;
        });
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) insertedItems.incrementAndGet();
            return null;
        });

        try {
            new StockXExcelDelistTaskRunner(account, 91L, "STANDARD", new StockXClient(),
                    taskMapper, taskItemMapper, 1).run();

            assertThat(insertedItems).hasValue(0);
            assertThat(finalStatus.get()).isEqualTo(TaskDO.TaskStatusEnum.SUCCESS.getCode());
        } finally {
            ShoesContext.loadDelistExcel(accountName, "STANDARD", List.of());
        }
    }

    @Test
    void usesTheTaskSnapshotInsteadOfTheMutableAccountExcel() {
        String accountName = "delist-snapshot-account";
        StockXDelistInputExcel currentAccountInput = new StockXDelistInputExcel();
        currentAccountInput.setListingId("current-account-listing");
        ShoesContext.loadDelistExcel(accountName, "STANDARD", List.of(currentAccountInput));

        StockXDelistInputExcel taskSnapshot = new StockXDelistInputExcel();
        taskSnapshot.setListingId("task-snapshot-listing");
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        AtomicReference<List<String>> submittedIds = new AtomicReference<>();
        StockXClient client = new StockXClient() {
            @Override
            public String deleteItems(List<String> idList, StockXAccount ignored) {
                submittedIds.set(List.copyOf(idList));
                return "batch-1";
            }

            @Override
            public java.util.Map<String, String> verifyDeleteBatch(
                    String batchId, List<String> listingIds, StockXAccount ignored,
                    Supplier<Boolean> cancelled) {
                return java.util.Map.of("task-snapshot-listing", "下架成功");
            }
        };
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> null);
        AtomicInteger itemId = new AtomicInteger();
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                ((cn.ken.shoes.model.entity.TaskItemDO) args[0]).setId((long) itemId.incrementAndGet());
                return 1;
            }
            if ("countSuccessfulDelistsByTaskId".equals(method)) return 1L;
            return null;
        });

        try {
            new StockXExcelDelistTaskRunner(account, 92L, "STANDARD", client,
                    taskMapper, taskItemMapper, 0, List.of(taskSnapshot)).run();

            assertThat(submittedIds.get()).containsExactly("task-snapshot-listing");
        } finally {
            ShoesContext.loadDelistExcel(accountName, "STANDARD", List.of());
        }
    }

    @Test
    void fullModeFetchesEveryListingPageBeforeDeleting() {
        StockXAccount account = new StockXAccount();
        account.setName("delist-all-account");
        AtomicInteger queriedPages = new AtomicInteger();
        AtomicReference<List<String>> submittedIds = new AtomicReference<>();
        StockXClient client = new StockXClient() {
            @Override
            public com.alibaba.fastjson.JSONObject querySellingItemsByInventoryType(
                    String inventoryType, Integer pageNumber, StockXAccount ignored) {
                queriedPages.incrementAndGet();
                com.alibaba.fastjson.JSONObject item = new com.alibaba.fastjson.JSONObject(true)
                        .fluentPut("id", "listing-" + pageNumber)
                        .fluentPut("styleId", "STYLE-" + pageNumber)
                        .fluentPut("size", String.valueOf(pageNumber));
                return new com.alibaba.fastjson.JSONObject(true)
                        .fluentPut("hasMore", pageNumber == 1)
                        .fluentPut("items", new com.alibaba.fastjson.JSONArray(java.util.List.of(item)));
            }

            @Override
            public String deleteItems(List<String> idList, StockXAccount ignored) {
                submittedIds.set(List.copyOf(idList));
                return "batch-all";
            }

            @Override
            public java.util.Map<String, String> verifyDeleteBatch(
                    String batchId, List<String> listingIds, StockXAccount ignored,
                    Supplier<Boolean> cancelled) {
                return java.util.Map.of(
                        "listing-1", "下架成功",
                        "listing-2", "下架成功");
            }
        };
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> null);
        AtomicInteger itemId = new AtomicInteger();
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                ((cn.ken.shoes.model.entity.TaskItemDO) args[0]).setId((long) itemId.incrementAndGet());
                return 1;
            }
            if ("countSuccessfulDelistsByTaskId".equals(method)) return 2L;
            return null;
        });

        TaskInputSnapshotStore snapshotStore = mock(TaskInputSnapshotStore.class);
        new StockXExcelDelistTaskRunner(account, 93L, "STANDARD", DelistMode.ALL, client,
                taskMapper, taskItemMapper, snapshotStore, 0, List.of()).run();

        assertThat(queriedPages).hasValue(2);
        assertThat(submittedIds.get()).containsExactly("listing-1", "listing-2");
        verify(snapshotStore).saveDelist(eq(93L), argThat(items ->
                items.stream().map(StockXDelistInputExcel::getListingId).toList()
                        .equals(List.of("listing-1", "listing-2"))));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object result = invocation.call(method.getName(), args == null ? new Object[0] : args);
                    if (result != null || method.getReturnType() == void.class) return result;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    private static com.alibaba.fastjson.JSONObject listing(String id, String styleId, String size, String euSize) {
        return new com.alibaba.fastjson.JSONObject(true)
                .fluentPut("id", id)
                .fluentPut("styleId", styleId)
                .fluentPut("size", size)
                .fluentPut("euSize", euSize);
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args);
    }
}
