package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import com.alibaba.excel.EasyExcel;
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

class TaskControllerPurchaseGuidanceTest {

    @Test
    void startsGuidanceWithModelAndSizeOnly() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startPurchaseGuidance(eq("account-1"), anyList())).thenReturn(321L);
        TaskController controller = controller(manager);

        Result<String> result = controller.startPurchaseGuidance(
                excelFile(List.of(row("DD1391-100", "EU 44"))), "account-1");

        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<List<ModelNoSearchExcel>> rows = ArgumentCaptor.forClass(List.class);
        verify(manager).startPurchaseGuidance(eq("account-1"), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(input -> {
            assertThat(input.getModelNo()).isEqualTo("DD1391-100");
            assertThat(input.getSize()).isEqualTo("EU 44");
        });
    }

    @Test
    void rejectsGuidanceWhenModelOrSizeIsMissing() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = controller(manager);

        Result<String> result = controller.startPurchaseGuidance(
                excelFile(List.of(row("DD1391-100", null))), "account-1");

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    private static TaskController controller(TaskExecutorManager manager) throws Exception {
        TaskController controller = new TaskController();
        Field field = TaskController.class.getDeclaredField("taskExecutorManager");
        field.setAccessible(true);
        field.set(controller, manager);
        return controller;
    }

    private static ModelNoSearchExcel row(String modelNo, String size) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        row.setSize(size);
        return row;
    }

    private static MockMultipartFile excelFile(List<ModelNoSearchExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, ModelNoSearchExcel.class).sheet().doWrite(rows);
        return new MockMultipartFile("file", "purchase-guidance.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
}
