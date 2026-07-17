package cn.ken.shoes.task;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
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

import static org.assertj.core.api.Assertions.assertThat;

class StockXExcelDelistTaskRunnerTest {

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

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args);
    }
}
