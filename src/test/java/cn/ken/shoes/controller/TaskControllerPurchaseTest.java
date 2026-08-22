package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.manager.TaskExecutorManager;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskControllerPurchaseTest {

    @Test
    void startsTheSelectedPurchaseOperation() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startPurchase("account-a", StockXPurchaseOperation.HISTORY)).thenReturn(105L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startPurchase(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("operation", "history"));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("105");
        verify(manager).startPurchase("account-a", StockXPurchaseOperation.HISTORY);
    }

    @Test
    void rejectsUnknownOperationAtTheApiBoundary() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startPurchase(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("operation", "checkout"));

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
