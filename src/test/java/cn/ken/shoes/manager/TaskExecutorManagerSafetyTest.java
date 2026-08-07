package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorManagerSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void exactModelSearchIsStoredAsAListingTaskAndNormalizedToOnePage() throws Exception {
        String accountName = "exact-search-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));

        AtomicReference<TaskDO> createdTask = new AtomicReference<>();
        AtomicReference<String> executedSorts = new AtomicReference<>();
        AtomicLong ids = new AtomicLong(300);
        CountDownLatch executed = new CountDownLatch(1);
        TaskMapper taskMapper = (TaskMapper) Proxy.newProxyInstance(
                TaskMapper.class.getClassLoader(), new Class<?>[]{TaskMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        TaskDO task = (TaskDO) args[0];
                        task.setId(ids.incrementAndGet());
                        createdTask.set(task);
                        return 1;
                    }
                    if (method.getReturnType() == int.class) return 1;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        StockXService service = new StockXService() {
            @Override
            public boolean searchAndList(StockXAccount ignored, Long taskId, String keywords, String sorts,
                                         int pageCount, String searchType, int maxListCount,
                                         boolean modelNoSearch, Map<String, Set<String>> modelNoSizeFilters) {
                executedSorts.set(sorts + ":" + pageCount + ":" + modelNoSearch);
                executed.countDown();
                return false;
            }
        };
        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskMapper", taskMapper);
        setField(manager, "stockXService", service);

        try {
            Long taskId = manager.startSearchList(accountName, "IF4396-104",
                    "lowest_ask,highest_bid", 25, "shoes", 0, true);

            assertThat(taskId).isEqualTo(301L);
            assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executedSorts.get()).isEqualTo("featured:1:true");
            assertThat(createdTask.get().getTaskType()).isEqualTo("listing");
            assertThat(createdTask.get().getParams()).contains(
                    "\"searchMode\":\"model_no\"", "\"pageCount\":1", "\"sorts\":\"featured\"");
        } finally {
            TaskSwitch.clearSearchListRunState(301L);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void excelSearchAndFullScanCanRunTogetherWithoutSharingExcelInput() throws Exception {
        String accountName = "parallel-price-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));
        ShoesContext.PriceDownConfig uploadedRule = new ShoesContext.PriceDownConfig(321, false);
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put("EXCEL:42", uploadedRule);

        TaskExecutorManager manager = new TaskExecutorManager();
        AtomicLong ids = new AtomicLong(200);
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
        CountDownLatch release = new CountDownLatch(1);
        StockXService service = new StockXService() {
            @Override
            public void priceDownWithExcelForAccount(StockXAccount ignored, String inventoryType) {
                awaitRelease(release);
                throw new IllegalStateException("stop-full-scan");
            }

            @Override
            public void priceDownWithExcelForAccount(StockXAccount ignored, String inventoryType,
                                                     ListingFetchMode fetchMode) {
                awaitRelease(release);
                throw new IllegalStateException("stop-excel-search");
            }
        };
        setField(manager, "taskMapper", taskMapper);
        setField(manager, "stockXService", service);
        setField(manager, "taskInputSnapshotStore", new TaskInputSnapshotStore(tempDir));

        try {
            Long excelTaskId = manager.startExcelPriceDown(
                    accountName, "STANDARD", true, false, "markup", 1800,
                    ListingFetchMode.EXCEL_SEARCH);
            ShoesContext.PriceDownConfig fullScanRule = new ShoesContext.PriceDownConfig(654, false);
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            ShoesContext.getPriceDownMap(accountName, "STANDARD").put("FULL:43", fullScanRule);
            Long fullScanTaskId = manager.startExcelPriceDown(
                    accountName, "STANDARD", true, true, "markup", 1800,
                    ListingFetchMode.ALL);

            assertThat(excelTaskId).isNotNull();
            assertThat(fullScanTaskId).isNotNull().isNotEqualTo(excelTaskId);
            assertThat(ShoesContext.getPriceDownMap(accountName, "STANDARD"))
                    .containsOnlyKeys("FULL:43")
                    .containsEntry("FULL:43", fullScanRule);
            assertThat(TaskSwitch.getPriceDownInput(
                    accountName, "STANDARD", ListingFetchMode.EXCEL_SEARCH))
                    .containsOnlyKeys("EXCEL:42");
            assertThat(TaskSwitch.getPriceDownInput(
                    accountName, "STANDARD", ListingFetchMode.ALL))
                    .containsOnlyKeys("FULL:43");
        } finally {
            release.countDown();
            TaskSwitch.clearExcelState(accountName, "STANDARD");
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void multipleSearchListTasksCanRunConcurrentlyForTheSameAccount() throws Exception {
        String accountName = "concurrent-search-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));

        AtomicLong ids = new AtomicLong(400);
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
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> runningTaskIds = java.util.Collections.synchronizedList(new ArrayList<>());
        StockXService service = new StockXService() {
            @Override
            public boolean searchAndList(StockXAccount ignored, Long taskId, String keywords, String sorts,
                                         int pageCount, String searchType, int maxListCount,
                                         boolean modelNoSearch, Map<String, Set<String>> modelNoSizeFilters) {
                runningTaskIds.add(taskId);
                bothRunning.countDown();
                awaitRelease(release);
                return false;
            }
        };
        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskMapper", taskMapper);
        setField(manager, "stockXService", service);

        Long firstTaskId = null;
        Long secondTaskId = null;
        try {
            firstTaskId = manager.startSearchList(accountName, "jordan retro",
                    "featured", 3, "shoes", 0, false);
            secondTaskId = manager.startSearchList(accountName, "yeezy slides",
                    "featured", 3, "shoes", 0, false);

            assertThat(firstTaskId).isNotNull();
            assertThat(secondTaskId).isNotNull().isNotEqualTo(firstTaskId);
            assertThat(bothRunning.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(runningTaskIds).containsExactlyInAnyOrder(firstTaskId, secondTaskId);
            assertThat(TaskSwitch.getAllSearchListTaskIds())
                    .contains(firstTaskId, secondTaskId);
        } finally {
            release.countDown();
            TaskSwitch.clearSearchListRunState(firstTaskId);
            TaskSwitch.clearSearchListRunState(secondTaskId);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    @Test
    void rerunningANonExcelPriceTaskUsesAnEmptyRuntimeInputWithoutDeletingSavedRules() throws Exception {
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
        setField(manager, "taskInputSnapshotStore", new TaskInputSnapshotStore(tempDir));

        TaskDO source = new TaskDO();
        source.setPlatform("stockx");
        source.setTaskType("price_down");
        source.setAccountName(accountName);
        source.setParams("{\"inventoryType\":\"STANDARD\",\"hasExcel\":false,\"processOutsideExcel\":true}");

        try {
            assertThat(manager.rerunTask(source)).isNotNull();
            assertThat(TaskSwitch.getPriceDownInput(
                    accountName, "STANDARD", ListingFetchMode.ALL)).isEmpty();
            assertThat(ShoesContext.getPriceDownMap(accountName, "STANDARD"))
                    .containsOnlyKeys("STALE:42");
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

    @Test
    void excelModelNoSearchTaskIsRestoredFromSnapshotOnRestart() throws Exception {
        String accountName = "restart-model-no-account";
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setEnabled(true);
        StockXConfig.setAccounts(List.of(account));

        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        snapshots.saveSearchModelNoInput(900L, List.of(
                modelNoRow("IF4396-104"), modelNoRow("DZ5485-612")));

        AtomicLong ids = new AtomicLong(900);
        AtomicReference<String> executedKeywords = new AtomicReference<>();
        CountDownLatch executed = new CountDownLatch(1);
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
            public boolean searchAndList(StockXAccount ignored, Long taskId, String keywords, String sorts,
                                         int pageCount, String searchType, int maxListCount,
                                         boolean modelNoSearch, Map<String, Set<String>> modelNoSizeFilters) {
                executedKeywords.set(keywords + "|" + modelNoSearch);
                executed.countDown();
                return false;
            }
        };
        TaskExecutorManager manager = new TaskExecutorManager();
        setField(manager, "taskMapper", taskMapper);
        setField(manager, "stockXService", service);
        setField(manager, "taskInputSnapshotStore", snapshots);

        TaskDO interrupted = new TaskDO();
        interrupted.setId(900L);
        interrupted.setPlatform("stockx");
        interrupted.setTaskType("listing");
        interrupted.setAccountName(accountName);
        interrupted.setParams("{\"searchMode\":\"model_no\",\"modelNoSearch\":true,"
                + "\"searchType\":\"shoes\",\"maxListCount\":0,\"modelNoCount\":2}");

        try {
            assertThat(manager.resumePausedTask(interrupted)).isEqualTo(900L);
            assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executedKeywords.get()).isEqualTo("IF4396-104\nDZ5485-612|true");
        } finally {
            TaskSwitch.clearSearchListRunState(900L);
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    private static ModelNoSearchExcel modelNoRow(String modelNo) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        return row;
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
