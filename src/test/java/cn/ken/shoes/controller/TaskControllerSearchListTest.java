package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.TaskExecutorManager;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskControllerSearchListTest {

    @Test
    void modelNumberSearchAlwaysUsesOneExactPage() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startSearchList(
                "account-1", "IF4396-104", "featured", 1, "shoes", 0, true))
                .thenReturn(101L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startSearchList(new JSONObject(true)
                .fluentPut("accountId", "account-1")
                .fluentPut("searchMode", "model_no")
                .fluentPut("keywords", "IF4396-104")
                .fluentPut("sorts", "lowest_ask,highest_bid")
                .fluentPut("pageCount", 25)
                .fluentPut("searchType", "shoes")
                .fluentPut("maxListCount", 0));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("101");
        verify(manager).startSearchList(
                "account-1", "IF4396-104", "featured", 1, "shoes", 0, true);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
