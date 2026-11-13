package cn.ken.shoes.manager;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskExecutorManagerShipmentsTest {
    @Test
    void rerunsWithTheOriginalAccountAndDeduplicatedShipmentTypes() {
        AtomicReference<List<StockXOrderCategory>> selected = new AtomicReference<>();
        TaskExecutorManager manager = new TaskExecutorManager() {
            @Override
            public Long startFetchShipments(String accountId, List<StockXOrderCategory> categories) {
                assertThat(accountId).isEqualTo("account-a");
                selected.set(categories);
                return 401L;
            }
        };
        TaskDO source = source("{\"shipmentTypes\":[\"packed\",\"received\",\"packed\"]}");
        assertThat(manager.canRerun(source)).isTrue();
        assertThat(manager.rerunTask(source)).isEqualTo(401L);
        assertThat(selected.get()).containsExactly(
                StockXOrderCategory.SHIPMENT_PACKED, StockXOrderCategory.SHIPMENT_RECEIVED);
        for (String params : List.of("{}", "{\"shipmentTypes\":[\"pending\"]}",
                "{\"shipmentTypes\":[\"packed\",\"bad\"]}", "{\"shipmentTypes\":\"packed\"}")) {
            selected.set(null);
            assertThat(manager.rerunTask(source(params))).isNull();
            assertThat(selected.get()).isNull();
        }
    }

    @Test
    void rejectsWrongCategoryAndReleasesReservationWhenTaskCreationFails() {
        TaskExecutorManager manager = new TaskExecutorManager();
        assertThat(manager.startFetchShipments("account-a", List.of(StockXOrderCategory.PENDING))).isNull();
        assertThat(manager.startFetchOrders("account-a", List.of(StockXOrderCategory.SHIPMENT_PACKED))).isNull();
        TaskMapper mapper = mock(TaskMapper.class);
        ReflectionTestUtils.setField(manager, "taskMapper", mapper);
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        account.setEnabled(true);
        try (var config = mockStatic(StockXConfig.class)) {
            config.when(() -> StockXConfig.getAccount("account-a")).thenReturn(account);
            when(mapper.insert(any(TaskDO.class))).thenThrow(new IllegalStateException("insert failed"));
            assertThatThrownBy(() -> manager.startFetchShipments("account-a",
                    List.of(StockXOrderCategory.SHIPMENT_PACKED))).hasMessage("insert failed");
            assertThat(TaskSwitch.isFetchOrdersRunning(TaskSwitch.fetchOrdersKey("account-a", true))).isFalse();
        } finally {
            TaskSwitch.clearFetchOrdersState(TaskSwitch.fetchOrdersKey("account-a", true));
        }
    }

    private TaskDO source(String params) {
        TaskDO task = new TaskDO();
        task.setId(7L);
        task.setPlatform("stockx");
        task.setTaskType("fetch_shipments");
        task.setAccountName("account-a");
        task.setParams(params);
        return task;
    }
}
