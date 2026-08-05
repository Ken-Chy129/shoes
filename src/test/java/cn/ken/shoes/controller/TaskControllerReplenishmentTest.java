package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.StockXReplenishmentService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskControllerReplenishmentTest {

    @Test
    void startsReplenishmentForTheSelectedLocalTimeRange() throws Exception {
        StockXReplenishmentService service = mock(StockXReplenishmentService.class);
        when(service.startManualAccount("account-a",
                Instant.parse("2026-08-04T20:00:00Z"), Instant.parse("2026-08-05T08:00:00Z")))
                .thenReturn(101L);
        TaskController controller = new TaskController();
        setField(controller, "replenishmentService", service);

        Result<String> result = controller.startReplenishment(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("soldStartTime", "2026-08-05 04:00:00")
                .fluentPut("soldEndTime", "2026-08-05 16:00:00"));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("101");
        verify(service).startManualAccount("account-a",
                Instant.parse("2026-08-04T20:00:00Z"), Instant.parse("2026-08-05T08:00:00Z"));
    }

    @Test
    void rejectsAnInvalidOrReversedTimeRangeBeforeStarting() throws Exception {
        StockXReplenishmentService service = mock(StockXReplenishmentService.class);
        TaskController controller = new TaskController();
        setField(controller, "replenishmentService", service);

        Result<String> result = controller.startReplenishment(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("soldStartTime", "2026-08-05 16:00:00")
                .fluentPut("soldEndTime", "2026-08-05 04:00:00"));

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(service);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
