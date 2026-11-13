package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.manager.TaskExecutorManager;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskControllerShipmentTest {
    private final TaskExecutorManager manager = mock(TaskExecutorManager.class);
    private final TaskController controller = new TaskController();

    TaskControllerShipmentTest() {
        ReflectionTestUtils.setField(controller, "taskExecutorManager", manager);
    }

    @Test
    void startsAnIndependentShipmentTaskAndDeduplicatesTypes() {
        when(manager.startFetchShipments("account-a", List.of(
                StockXOrderCategory.SHIPMENT_PACKED, StockXOrderCategory.SHIPMENT_RECEIVED))).thenReturn(42L);
        Result<String> result = controller.startFetchShipments(body(List.of("packed", "received", "packed")));
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("42");
        verify(manager).startFetchShipments("account-a", List.of(
                StockXOrderCategory.SHIPMENT_PACKED, StockXOrderCategory.SHIPMENT_RECEIVED));
    }

    @Test
    void rejectsMissingMalformedOrNonShipmentTypesBeforeStartingWork() {
        for (String json : List.of("{}", "{\"accountId\":\"a\"}",
                "{\"accountId\":\" \",\"shipmentTypes\":[\"packed\"]}",
                "{\"accountId\":123,\"shipmentTypes\":[\"packed\"]}",
                "{\"accountId\":\"a\",\"shipmentTypes\":\"packed\"}")) {
            assertThat(controller.startFetchShipments(JSONObject.parseObject(json)).getSuccess()).isFalse();
        }
        for (Object types : List.of(List.of(), List.of("pending"), List.of("packed", "invalid"), List.of(123))) {
            assertThat(controller.startFetchShipments(body(types)).getSuccess()).isFalse();
        }
        verifyNoInteractions(manager);
    }

    @Test
    void returnsAnErrorForDuplicateTaskOrMissingAccount() {
        when(manager.startFetchShipments(anyString(), anyList())).thenReturn(null);
        assertThat(controller.startFetchShipments(body(List.of("shipped"))).getSuccess()).isFalse();
    }

    @Test
    void keepsShipmentTypesOutOfTheOldOrderEndpoint() {
        Result<String> result = controller.startFetchOrders(new JSONObject()
                .fluentPut("accountId", "account-a").fluentPut("orderTypes", List.of("packed")));
        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    private JSONObject body(Object types) {
        return new JSONObject().fluentPut("accountId", "account-a").fluentPut("shipmentTypes", types);
    }
}
