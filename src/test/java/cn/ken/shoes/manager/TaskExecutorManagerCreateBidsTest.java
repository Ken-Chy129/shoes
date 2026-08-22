package cn.ken.shoes.manager;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidBatch;
import cn.ken.shoes.model.stockx.StockXBidCreateItem;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorManagerCreateBidsTest {

    @TempDir
    Path tempDir;

    @Test
    void createsPurchaseTaskAndPersistsBidInputForReruns() throws Exception {
        String accountName = "create-bids-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setCountry("US");
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));

        AtomicReference<TaskDO> createdTask = new AtomicReference<>();
        AtomicLong ids = new AtomicLong(300);
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                TaskDO task = (TaskDO) args[0];
                task.setId(ids.incrementAndGet());
                createdTask.set(task);
                return 1;
            }
            return primitiveDefault(method);
        });
        CountDownLatch submitted = new CountDownLatch(1);
        StockXClient client = client(submitted);
        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskMapper", taskMapper);
        setField(manager, "taskItemMapper", proxy(TaskItemMapper.class,
                (method, args) -> method.equals("insert") ? 1 : primitiveDefault(method)));
        setField(manager, "stockXClient", client);
        setField(manager, "taskInputSnapshotStore", snapshots);

        try {
            Long taskId = manager.startCreateBids(accountName,
                    List.of(bid("100289469", "US M 4.5", "1")));

            assertThat(taskId).isEqualTo(301L);
            assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(createdTask.get().getTaskType()).isEqualTo("purchase");
            assertThat(createdTask.get().getParams())
                    .contains("\"operation\":\"create_bids\"")
                    .contains("\"inputCount\":1");
            assertThat(snapshots.loadCreateBidsInput(taskId)).hasValueSatisfying(rows ->
                    assertThat(rows).singleElement().satisfies(row ->
                            assertThat(row.getPrice()).isEqualByComparingTo("1")));
        } finally {
            TaskSwitch.clearPurchaseState(accountName);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void rerunsCreateBidsOnlyFromTheOriginalTaskSnapshot() throws Exception {
        String accountName = "rerun-bids-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setCountry("US");
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));
        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        snapshots.saveCreateBidsInput(77L, List.of(bid("ORIGINAL", "US 9", "88")));
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicReference<BigDecimal> submittedAmount = new AtomicReference<>();
        StockXClient client = new StockXClient() {
            @Override
            public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                                StockXAccount ignored) {
                return new JSONObject(true)
                        .fluentPut("edges", new JSONArray())
                        .fluentPut("pageInfo", new JSONObject(true).fluentPut("hasNextPage", false));
            }

            @Override
            public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String searchType,
                                                                   String country, StockXAccount ignored) {
                StockXPriceExcel row = new StockXPriceExcel();
                row.setId("variant-rerun");
                row.setModelNo(modelNo);
                row.setUsmSize("9");
                return List.of(row);
            }

            @Override
            public StockXBidBatch createBids(List<StockXBidCreateItem> items, StockXAccount ignored) {
                submittedAmount.set(items.get(0).amount());
                submitted.countDown();
                return new StockXBidBatch("batch-rerun", "QUEUED");
            }
        };
        AtomicLong ids = new AtomicLong(400);
        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskInputSnapshotStore", snapshots);
        setField(manager, "stockXClient", client);
        setField(manager, "taskMapper", proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                ((TaskDO) args[0]).setId(ids.incrementAndGet());
                return 1;
            }
            return primitiveDefault(method);
        }));
        setField(manager, "taskItemMapper", proxy(TaskItemMapper.class,
                (method, args) -> method.equals("insert") ? 1 : primitiveDefault(method)));
        TaskDO source = new TaskDO();
        source.setId(77L);
        source.setPlatform("stockx");
        source.setTaskType("purchase");
        source.setAccountName(accountName);
        source.setParams("{\"operation\":\"create_bids\",\"inputCount\":1}");

        try {
            assertThat(manager.rerunTask(source)).isEqualTo(401L);
            assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(submittedAmount.get()).isEqualByComparingTo("88");
        } finally {
            TaskSwitch.clearPurchaseState(accountName);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    private static StockXClient client(CountDownLatch submitted) {
        return new StockXClient() {
            @Override
            public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                                StockXAccount ignored) {
                return new JSONObject(true)
                        .fluentPut("edges", new JSONArray())
                        .fluentPut("pageInfo", new JSONObject(true).fluentPut("hasNextPage", false));
            }

            @Override
            public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String searchType,
                                                                   String country, StockXAccount ignored) {
                StockXPriceExcel row = new StockXPriceExcel();
                row.setId("variant-1");
                row.setModelNo(modelNo);
                row.setUsmSize(modelNo.equals("ORIGINAL") ? "9" : "4.5");
                return List.of(row);
            }

            @Override
            public StockXBidBatch createBids(List<StockXBidCreateItem> items, StockXAccount ignored) {
                submitted.countDown();
                return new StockXBidBatch("batch-1", "QUEUED");
            }
        };
    }

    private static StockXBidInputExcel bid(String styleId, String size, String price) {
        StockXBidInputExcel row = new StockXBidInputExcel();
        row.setStyleId(styleId);
        row.setSize(size);
        row.setPrice(new BigDecimal(price));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (ignored, method, args) -> handler.invoke(method.getName(), args));
    }

    private static Object primitiveDefault(String method) {
        return 0;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskExecutorManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(String method, Object[] args);
    }
}
