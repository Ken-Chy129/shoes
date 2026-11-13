package cn.ken.shoes.manager;

import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.StockXCustodialPriceInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskExecutorManagerCustodialPriceTest {
    @Test void releasesReservationOnStartFailureAndDoesNotRerunOldUploads() {
        TaskExecutorManager manager = new TaskExecutorManager();
        TaskMapper tasks = mock(TaskMapper.class);
        ReflectionTestUtils.setField(manager, "taskMapper", tasks);
        StockXAccount account = new StockXAccount();
        account.setName("custodial-manager-fixture");
        account.setEnabled(true);
        StockXCustodialPriceInputExcel row = new StockXCustodialPriceInputExcel();
        row.setListingId("listing-1");
        row.setPrice(BigDecimal.TEN);
        try (var config = mockStatic(StockXConfig.class)) {
            config.when(() -> StockXConfig.getAccount(account.getName())).thenReturn(account);
            when(tasks.insert(any(TaskDO.class))).thenThrow(new IllegalStateException("insert failed"));
            assertThatThrownBy(() -> manager.startUpdateCustodialPrices(account.getName(), List.of(row)))
                    .hasMessage("insert failed");
            assertThat(TaskSwitch.tryStartCustodialPriceUpdate(account.getName())).isTrue();
        } finally {
            TaskSwitch.clearCustodialPriceUpdate(account.getName());
        }
        TaskDO source = new TaskDO();
        source.setPlatform("stockx");
        source.setTaskType("update_custodial_prices");
        assertThat(manager.canRerun(source)).isFalse();
        assertThat(manager.rerunTask(source)).isNull();
        assertThat(TaskTypeEnum.UPDATE_CUSTODIAL_PRICES.isResumeOnStartup()).isFalse();
    }
}
