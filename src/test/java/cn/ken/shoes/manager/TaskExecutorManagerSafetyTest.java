package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorManagerSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void rerunningANonExcelPriceTaskClearsStaleAccountExcelRules() throws Exception {
        String accountName = "no-excel-rerun-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));
        ShoesContext.getPriceDownMap(accountName, "STANDARD")
                .put("STALE:42", new ShoesContext.PriceDownConfig(999, false));

        TaskExecutorManager manager = new TaskExecutorManager();
        AtomicLong ids = new AtomicLong(100);
        TaskMapper taskMapper = (TaskMapper) Proxy.newProxyInstance(
                TaskMapper.class.getClassLoader(), new Class<?>[]{TaskMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        ((TaskDO) args[0]).setId(ids.incrementAndGet());
                        return 1;
                    }
                    if (method.getReturnType() == int.class) return 1;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        StockXService service = new StockXService() {
            @Override
            public void priceDownWithExcelForAccount(StockXAccount ignored, String inventoryType) {
                throw new IllegalStateException("stop-test-runner");
            }
        };
        setField(manager, "taskMapper", taskMapper);
        setField(manager, "stockXService", service);
        setField(manager, "configManager", new ConfigManager());
        setField(manager, "taskInputSnapshotStore", new TaskInputSnapshotStore(tempDir));

        TaskDO source = new TaskDO();
        source.setPlatform("stockx");
        source.setTaskType("price_down");
        source.setAccountName(accountName);
        source.setParams("{\"inventoryType\":\"STANDARD\",\"hasExcel\":false,\"processOutsideExcel\":true}");

        try {
            assertThat(manager.rerunTask(source)).isNotNull();
            assertThat(ShoesContext.getPriceDownMap(accountName, "STANDARD")).isEmpty();
        } finally {
            TaskSwitch.clearExcelState(accountName, "STANDARD");
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void rejectedExcelRerunDoesNotOverwriteTheRunningTasksRules() throws Exception {
        String accountName = "active-price-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));

        ShoesContext.PriceDownConfig activeRule = new ShoesContext.PriceDownConfig(888, false);
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put("ACTIVE:42", activeRule);
        TaskSwitch.setExcelRunning(accountName, "STANDARD", true);

        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        snapshots.savePriceDown(7L, new LinkedHashMap<>(java.util.Map.of(
                "HISTORICAL:43", new ShoesContext.PriceDownConfig(100, false))));

        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskInputSnapshotStore", snapshots);
        setField(manager, "configManager", new ConfigManager());

        TaskDO source = new TaskDO();
        source.setId(7L);
        source.setPlatform("stockx");
        source.setTaskType("price_down");
        source.setAccountName(accountName);
        source.setParams("{\"inventoryType\":\"STANDARD\",\"hasExcel\":true}");

        try {
            assertThat(manager.rerunTask(source)).isNull();
            assertThat(ShoesContext.getPriceDownMap(accountName, "STANDARD"))
                    .containsOnlyKeys("ACTIVE:42")
                    .containsEntry("ACTIVE:42", activeRule);
        } finally {
            TaskSwitch.clearExcelState(accountName, "STANDARD");
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void rerunWithMissingHistoricalExcelSnapshotDoesNotUseCurrentAccountRules() throws Exception {
        String accountName = "missing-snapshot-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));
        ShoesContext.getPriceDownMap(accountName, "STANDARD")
                .put("CURRENT:42", new ShoesContext.PriceDownConfig(999, false));

        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskInputSnapshotStore", new TaskInputSnapshotStore(tempDir));
        setField(manager, "configManager", new ConfigManager());

        TaskDO source = new TaskDO();
        source.setId(77L);
        source.setPlatform("stockx");
        source.setTaskType("price_down");
        source.setAccountName(accountName);
        source.setParams("{\"inventoryType\":\"STANDARD\",\"hasExcel\":true}");

        try {
            assertThat(manager.rerunTask(source)).isNull();
            assertThat(ShoesContext.getPriceDownMap(accountName, "STANDARD"))
                    .containsOnlyKeys("CURRENT:42");
        } finally {
            TaskSwitch.clearExcelState(accountName, "STANDARD");
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void startupRecoveryReportsFailureWhenTheReplacementTaskCannotStart() throws Exception {
        TaskExecutorManager manager = new TaskExecutorManager();
        TaskDO source = new TaskDO();
        source.setId(88L);
        source.setPlatform("stockx");
        source.setTaskType("price_down");
        source.setAccountName("missing-account");
        source.setParams("{\"inventoryType\":\"STANDARD\",\"hasExcel\":false}");

        Method resumeTask = TaskExecutorManager.class.getDeclaredMethod(
                "resumeTask", TaskDO.class, cn.ken.shoes.common.TaskTypeEnum.class);
        resumeTask.setAccessible(true);

        assertThat((Boolean) resumeTask.invoke(manager, source,
                cn.ken.shoes.common.TaskTypeEnum.PRICE_DOWN)).isFalse();
    }

    @Test
    void resumingPausedTaskWithMissingSnapshotDoesNotUseCurrentAccountRules() throws Exception {
        String accountName = "missing-resume-snapshot-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));
        ShoesContext.getPriceDownMap(accountName, "STANDARD")
                .put("CURRENT:43", new ShoesContext.PriceDownConfig(888, false));

        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskInputSnapshotStore", new TaskInputSnapshotStore(tempDir));
        setField(manager, "configManager", new ConfigManager());

        TaskDO paused = new TaskDO();
        paused.setId(99L);
        paused.setPlatform("stockx");
        paused.setTaskType("price_down");
        paused.setAccountName(accountName);
        paused.setParams("{\"inventoryType\":\"STANDARD\",\"hasExcel\":true}");

        try {
            assertThat(manager.resumePausedTask(paused)).isNull();
            assertThat(ShoesContext.getPriceDownMap(accountName, "STANDARD"))
                    .containsOnlyKeys("CURRENT:43");
        } finally {
            TaskSwitch.clearExcelState(accountName, "STANDARD");
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskExecutorManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
