package cn.ken.shoes.manager;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXBidUpdateInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXBidBatch;
import cn.ken.shoes.model.stockx.StockXBidFeePolicy;
import cn.ken.shoes.model.stockx.StockXBidUpdateItem;
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

class TaskExecutorManagerUpdateBidsTest {

    @TempDir
    Path tempDir;

    @Test
    void createsUpdateBidsTaskAndPersistsFeeSettingsAndRowFlags() throws Exception {
        String accountName = "update-bids-persist-account";
        List<StockXAccount> originalAccounts = installAccount(accountName);
        AtomicReference<TaskDO> createdTask = new AtomicReference<>();
        AtomicLong ids = new AtomicLong(500);
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        TaskExecutorManager manager = manager(ids, createdTask, snapshots,
                client(accountName, "89", submitted, new AtomicReference<>()), finished);

        try {
            Long taskId = manager.startUpdateBids(accountName,
                    List.of(update("bid-1", "200", "是")), 60L,
                    new StockXBidFeePolicy(true, new BigDecimal("0.08"),
                            new BigDecimal("6.25"), new BigDecimal("0.04"), true));

            assertThat(taskId).isEqualTo(501L);
            assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(createdTask.get().getParams())
                    .contains("\"operation\":\"update_bids\"")
                    .contains("\"interval\":60")
                    .contains("\"feeMonitorEnabled\":true")
                    .contains("\"merchantFeeRate\":0.08")
                    .contains("\"minMerchantFee\":6.25")
                    .contains("\"transferFeeRate\":0.04")
                    .contains("\"processOutsideExcel\":true");
            assertThat(snapshots.loadUpdateBidsInput(taskId)).hasValueSatisfying(rows ->
                    assertThat(rows).singleElement().satisfies(row -> {
                        assertThat(row.getBidId()).isEqualTo("bid-1");
                        assertThat(row.getPrice()).isEqualByComparingTo("200");
                        assertThat(row.getFeeConfigEnabled()).isEqualTo("是");
                    }));
        } finally {
            TaskSwitch.clearPurchaseState(accountName);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void rerunsUpdateBidsWithTheOriginalFeePolicyAndExcelSnapshot() throws Exception {
        String accountName = "update-bids-rerun-account";
        List<StockXAccount> originalAccounts = installAccount(accountName);
        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        snapshots.saveUpdateBidsInput(77L, List.of(update("bid-1", "200", "是")));
        AtomicReference<BigDecimal> submittedAmount = new AtomicReference<>();
        AtomicReference<TaskDO> createdTask = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        TaskExecutorManager manager = manager(new AtomicLong(600), createdTask,
                snapshots, client(accountName, "88", submitted, submittedAmount), finished);
        TaskDO source = new TaskDO();
        source.setId(77L);
        source.setPlatform("stockx");
        source.setTaskType("purchase");
        source.setAccountName(accountName);
        source.setParams("""
                {"operation":"update_bids","inputCount":1,"interval":60,
                 "feeMonitorEnabled":true,"merchantFeeRate":0.08,
                 "minMerchantFee":6.25,"transferFeeRate":0.04,
                 "processOutsideExcel":false}
                """);

        try {
            Long rerunTaskId = manager.rerunTask(source);

            assertThat(rerunTaskId).isEqualTo(601L);
            assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(submittedAmount.get()).isEqualByComparingTo("1");
            assertThat(createdTask.get().getParams())
                    .contains("\"merchantFeeRate\":0.08")
                    .contains("\"processOutsideExcel\":false");
            assertThat(snapshots.loadUpdateBidsInput(rerunTaskId)).hasValueSatisfying(rows ->
                    assertThat(rows).singleElement().satisfies(row ->
                            assertThat(row.getFeeConfigEnabled()).isEqualTo("是")));
        } finally {
            TaskSwitch.clearPurchaseState(accountName);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    private static TaskExecutorManager manager(
            AtomicLong ids, AtomicReference<TaskDO> createdTask,
            TaskInputSnapshotStore snapshots, StockXClient client, CountDownLatch finished)
            throws Exception {
        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskInputSnapshotStore", snapshots);
        setField(manager, "stockXClient", client);
        setField(manager, "taskMapper", proxy(TaskMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                TaskDO task = (TaskDO) args[0];
                task.setId(ids.incrementAndGet());
                createdTask.set(task);
                return 1;
            }
            if (method.equals("updateTaskStatus")
                    && TaskDO.TaskStatusEnum.CANCEL.getCode().equals(args[1])) {
                finished.countDown();
            }
            return primitiveDefault(method);
        }));
        setField(manager, "taskItemMapper", proxy(TaskItemMapper.class, (method, args) -> {
            if (method.equals("insert")) {
                ((TaskItemDO) args[0]).setId(ids.incrementAndGet());
                return 1;
            }
            return primitiveDefault(method);
        }));
        return manager;
    }

    private static StockXClient client(
            String accountName, String highestBid, CountDownLatch submitted,
            AtomicReference<BigDecimal> submittedAmount) {
        return new StockXClient() {
            @Override
            public JSONObject queryPurchasePage(StockXPurchaseOperation operation, String after,
                                                StockXAccount ignored) {
                return page(activeBid(highestBid));
            }

            @Override
            public StockXBidBatch updateBids(List<StockXBidUpdateItem> items,
                                             StockXAccount ignored) {
                submittedAmount.set(items.get(0).amount());
                TaskSwitch.cancelPurchase(accountName);
                submitted.countDown();
                return new StockXBidBatch("update-batch-1", "QUEUED");
            }
        };
    }

    private static JSONObject activeBid(String highestBid) {
        JSONObject state = new JSONObject(true)
                .fluentPut("bidInventoryTypes", new JSONObject(true)
                        .fluentPut("standard", new JSONObject(true)
                                .fluentPut("highest", new JSONObject(true)
                                        .fluentPut("amount", highestBid))))
                .fluentPut("askServiceLevels", new JSONObject(true)
                        .fluentPut("standard", new JSONObject(true)
                                .fluentPut("lowest", new JSONObject(true)
                                        .fluentPut("amount", "100"))));
        return new JSONObject(true)
                .fluentPut("id", "bid-1")
                .fluentPut("amount", "80")
                .fluentPut("currencyCode", "USD")
                .fluentPut("productVariant", new JSONObject(true)
                        .fluentPut("id", "variant-1")
                        .fluentPut("market", new JSONObject(true).fluentPut("state", state))
                        .fluentPut("traits", new JSONObject(true).fluentPut("size", "9"))
                        .fluentPut("product", new JSONObject(true)
                                .fluentPut("title", "Test product")
                                .fluentPut("styleId", "STYLE-1")));
    }

    private static JSONObject page(JSONObject node) {
        return new JSONObject(true)
                .fluentPut("edges", new JSONArray(List.of(
                        new JSONObject(true).fluentPut("node", node))))
                .fluentPut("pageInfo", new JSONObject(true)
                        .fluentPut("hasNextPage", false));
    }

    private static StockXBidUpdateInputExcel update(
            String bidId, String price, String feeConfigEnabled) {
        StockXBidUpdateInputExcel row = new StockXBidUpdateInputExcel();
        row.setBidId(bidId);
        row.setPrice(new BigDecimal(price));
        row.setFeeConfigEnabled(feeConfigEnabled);
        return row;
    }

    private static List<StockXAccount> installAccount(String accountName) {
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setCountry("US");
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));
        return originalAccounts;
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
