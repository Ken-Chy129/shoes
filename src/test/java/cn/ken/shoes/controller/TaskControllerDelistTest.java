package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.DelistMode;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskControllerDelistTest {

    @Test
    void startsFullDelistWithoutRequiringUploadedExcel() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startDelist("account-1", "STANDARD", DelistMode.ALL)).thenReturn(101L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startDelist(new JSONObject(true)
                .fluentPut("accountId", "account-1")
                .fluentPut("inventoryType", "STANDARD")
                .fluentPut("delistMode", "all"));

        assertThat(result.getSuccess()).isTrue();
        verify(manager).startDelist("account-1", "STANDARD", DelistMode.ALL);
    }

    @Test
    void rejectsUnknownDelistModeAtTheApiBoundary() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startDelist(new JSONObject(true)
                .fluentPut("accountId", "account-1")
                .fluentPut("inventoryType", "STANDARD")
                .fluentPut("delistMode", "unknown"));

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    @Test
    void keepsTheOldExcelEndpointCompatible() throws Exception {
        String accountId = "legacy-delist-account";
        StockXDelistInputExcel input = new StockXDelistInputExcel();
        input.setListingId("listing-1");
        ShoesContext.loadDelistExcel(accountId, "STANDARD", List.of(input));
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startDelist(accountId, "STANDARD", DelistMode.EXCEL)).thenReturn(102L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        try {
            Result<String> result = controller.startExcelDelist(new JSONObject(true)
                    .fluentPut("accountId", accountId)
                    .fluentPut("inventoryType", "STANDARD"));

            assertThat(result.getSuccess()).isTrue();
            verify(manager).startDelist(accountId, "STANDARD", DelistMode.EXCEL);
        } finally {
            ShoesContext.loadDelistExcel(accountId, "STANDARD", List.of());
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
