package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskControllerSearchListTest {

    @Test
    void rejectsModelNumberSearchOnKeywordEndpoint() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = controller(manager);

        Result<String> result = controller.startSearchList(new JSONObject(true)
                .fluentPut("accountId", "account-1")
                .fluentPut("searchMode", "model_no")
                .fluentPut("keywords", "IF4396-104")
                .fluentPut("sorts", "featured"));

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    @Test
    void startsModelNumberSearchFromUploadedExcel() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startModelNoSearchList(eq("account-1"), anyList(), eq("shoes"), eq(0)))
                .thenReturn(101L);
        TaskController controller = controller(manager);

        Result<String> result = controller.startModelNoSearchListByExcel(
                excelFile(List.of(modelNoRow("IF4396-104"), modelNoRow("DZ5485-612"))),
                "account-1", "shoes", 0);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("101");
        ArgumentCaptor<List<ModelNoSearchExcel>> rows = ArgumentCaptor.forClass(List.class);
        verify(manager).startModelNoSearchList(eq("account-1"), rows.capture(), eq("shoes"), eq(0));
        assertThat(rows.getValue()).extracting(ModelNoSearchExcel::getModelNo)
                .containsExactly("IF4396-104", "DZ5485-612");
    }

    @Test
    void rejectsEmptyModelNumberExcel() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = controller(manager);

        Result<String> result = controller.startModelNoSearchListByExcel(
                excelFile(List.of()), "account-1", "shoes", 0);

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    private static TaskController controller(TaskExecutorManager manager) throws Exception {
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);
        return controller;
    }

    private static ModelNoSearchExcel modelNoRow(String modelNo) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        return row;
    }

    private static MockMultipartFile excelFile(List<ModelNoSearchExcel> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, ModelNoSearchExcel.class).sheet().doWrite(rows);
        return new MockMultipartFile("file", "model-no.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
