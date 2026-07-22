package cn.ken.shoes.task;

import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockXExcelPriceDownTaskRunnerTest {

    @Test
    void passesExcelSearchModeToEachPriceDownRound() {
        StockXAccount account = new StockXAccount();
        account.setName("search-mode-account");
        AtomicBoolean receivedSearchMode = new AtomicBoolean();
        TaskMapper taskMapper = emptyTaskMapper();
        StockXService service = new StockXService() {
            @Override
            public void priceDownWithExcelForAccount(StockXAccount ignored, String inventoryType,
                                                     ListingFetchMode fetchMode) {
                receivedSearchMode.set(fetchMode == ListingFetchMode.EXCEL_SEARCH);
                TaskSwitch.cancelExcel("search-mode-account", "STANDARD");
            }
        };
        TaskSwitch.setExcelTaskId("search-mode-account", "STANDARD", 89L);
        TaskSwitch.resetExcelCancel("search-mode-account", "STANDARD");

        try {
            new StockXExcelPriceDownTaskRunner(account, "STANDARD", ListingFetchMode.EXCEL_SEARCH,
                    service, taskMapper).run();

            assertThat(receivedSearchMode).isTrue();
        } finally {
            TaskSwitch.clearExcelState("search-mode-account", "STANDARD");
        }
    }

    @Test
    void persistentRateLimitPausesTaskInsteadOfFailingIt() {
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        AtomicReference<String> pauseReason = new AtomicReference<>();
        AtomicReference<String> failedReason = new AtomicReference<>();
        TaskMapper taskMapper = (TaskMapper) Proxy.newProxyInstance(
                TaskMapper.class.getClassLoader(), new Class<?>[]{TaskMapper.class},
                (proxy, method, args) -> {
                    if ("updateTaskPaused".equals(method.getName())) pauseReason.set((String) args[1]);
                    if ("updateTaskFailed".equals(method.getName())) failedReason.set((String) args[1]);
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        StockXService service = new StockXService() {
            @Override
            public void priceDownWithExcelForAccount(StockXAccount ignored, String inventoryType) {
                throw new StockXRateLimitException("account-a", 300_000L,
                        "StockX持续限流，任务已暂停");
            }
        };
        TaskSwitch.setExcelTaskId("account-a", "STANDARD", 88L);
        TaskSwitch.resetExcelCancel("account-a", "STANDARD");

        try {
            new StockXExcelPriceDownTaskRunner(account, "STANDARD", service, taskMapper).run();

            assertThat(pauseReason.get()).isEqualTo("StockX持续限流，任务已暂停");
            assertThat(failedReason.get()).isNull();
            assertThat(TaskSwitch.isExcelRunning("account-a", "STANDARD")).isFalse();
        } finally {
            TaskSwitch.clearExcelState("account-a", "STANDARD");
        }
    }

    private static TaskMapper emptyTaskMapper() {
        return (TaskMapper) Proxy.newProxyInstance(
                TaskMapper.class.getClassLoader(), new Class<?>[]{TaskMapper.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }
}
