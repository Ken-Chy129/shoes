package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskControllerModelNoSearchUploadTest {

    @Test
    void passesOptionalSizeFiltersToModelNumberSearchTask() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startSearchList(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyBoolean(), anyMap())).thenReturn(123L);

        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        MockMultipartFile file = excelFile(List.of(
                row("STYLE-1", "9.5"),
                row("STYLE-1", "42"),
                row("STYLE-2", null)
        ));

        Result<String> result = controller.startModelNoSearchList(file, "account-1", 0);

        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<Map<String, Set<String>>> filtersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(manager).startSearchList(
                eq("account-1"), eq("STYLE-1\nSTYLE-2"), eq("featured"), eq(1), eq("shoes"), eq(0), eq(true),
                filtersCaptor.capture());
        assertThat(filtersCaptor.getValue())
                .containsEntry("STYLE-1", Set.of("9.5", "42"))
                .doesNotContainKey("STYLE-2");
    }

    @Test
    void keepsLegacyModelNumberOnlyExcelUnrestricted() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startSearchList(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyBoolean(), anyMap())).thenReturn(456L);

        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        LegacyModelNoExcel row = new LegacyModelNoExcel();
        row.setModelNo("STYLE-LEGACY");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, LegacyModelNoExcel.class).sheet().doWrite(List.of(row));
        MockMultipartFile file = new MockMultipartFile(
                "file", "legacy.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());

        Result<String> result = controller.startModelNoSearchList(file, "account-1", 0);

        assertThat(result.getSuccess()).isTrue();
        verify(manager).startSearchList(
                eq("account-1"), eq("STYLE-LEGACY"), eq("featured"), eq(1), eq("shoes"), eq(0), eq(true),
                eq(Map.of()));
    }

    private static MockMultipartFile excelFile(List<ModelNoSearchExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, ModelNoSearchExcel.class).sheet().doWrite(rows);
        return new MockMultipartFile(
                "file", "model-no.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private static ModelNoSearchExcel row(String modelNo, String size) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        row.setSize(size);
        return row;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Data
    private static class LegacyModelNoExcel {
        @ExcelProperty("货号")
        private String modelNo;
    }
}
