package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskControllerModelNoSearchUploadTest {

    @Test
    void startsPriceFetchWithRequiredModelAndSizeRows() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startModelSearchPriceFetch(eq("account-1"), anyList())).thenReturn(123L);
        TaskController controller = controller(manager);

        Result<String> result = controller.startModelNoSearchList(
                excelFile(List.of(priceRow("STYLE-1", "US 9")), ModelNoSearchExcel.class),
                "account-1", "fetch_price");

        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<List<ModelNoSearchExcel>> rows = ArgumentCaptor.forClass(List.class);
        verify(manager).startModelSearchPriceFetch(eq("account-1"), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getModelNo()).isEqualTo("STYLE-1");
            assertThat(row.getSize()).isEqualTo("US 9");
        });
    }

    @Test
    void rejectsPriceFetchWhenSizeIsMissing() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = controller(manager);

        Result<String> result = controller.startModelNoSearchList(
                excelFile(List.of(priceRow("STYLE-1", null)), ModelNoSearchExcel.class),
                "account-1", "fetch_price");

        assertThat(result.getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    @Test
    void startsDirectListingWithVariantPriceAndQuantityRows() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startModelSearchListing(eq("account-1"), anyList())).thenReturn(456L);
        TaskController controller = controller(manager);
        ModelSearchListingExcel row = new ModelSearchListingExcel();
        row.setVariantId("variant-1");
        row.setTargetPrice(new BigDecimal("305"));
        row.setQuantity(2);

        Result<String> result = controller.startModelNoSearchList(
                excelFile(List.of(row), ModelSearchListingExcel.class),
                "account-1", "create_listing");

        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<List<ModelSearchListingExcel>> rows = ArgumentCaptor.forClass(List.class);
        verify(manager).startModelSearchListing(eq("account-1"), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(input -> {
            assertThat(input.getVariantId()).isEqualTo("variant-1");
            assertThat(input.getTargetPrice()).isEqualByComparingTo("305");
            assertThat(input.getQuantity()).isEqualTo(2);
        });
    }

    private static TaskController controller(TaskExecutorManager manager) throws Exception {
        TaskController controller = new TaskController();
        Field field = TaskController.class.getDeclaredField("taskExecutorManager");
        field.setAccessible(true);
        field.set(controller, manager);
        return controller;
    }

    private static ModelNoSearchExcel priceRow(String modelNo, String size) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        row.setSize(size);
        return row;
    }

    private static <T> MockMultipartFile excelFile(List<T> rows, Class<T> head) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, head).sheet().doWrite(rows);
        return new MockMultipartFile(
                "file", "model-search.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }
}
