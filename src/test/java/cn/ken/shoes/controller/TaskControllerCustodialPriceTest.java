package cn.ken.shoes.controller;

import cn.ken.shoes.manager.TaskExecutorManager;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskControllerCustodialPriceTest {
    private final TaskExecutorManager manager = mock(TaskExecutorManager.class);
    private final TaskController controller = new TaskController();

    TaskControllerCustodialPriceTest() {
        ReflectionTestUtils.setField(controller, "taskExecutorManager", manager);
    }

    @Test void acceptsExportListingIdAndExplicitTargetPrice() throws Exception {
        when(manager.startUpdateCustodialPrices(eq("fixture"), argThat(rows ->
                rows.size() == 1 && rows.getFirst().getListingId().equals("id-1")
                        && rows.getFirst().getPrice().toPlainString().equals("123")))).thenReturn(81L);
        var result = controller.startUpdateCustodialPrices(excel(List.of(List.of(" id-1 ", "123.00"))), "fixture");
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("81");
    }

    @Test void rejectsInvalidInputsBeforeCreatingAnyTask() throws Exception {
        for (List<List<String>> rows : List.of(
                List.<List<String>>of(), List.of(List.of("", "100")), List.of(List.of("id", "")),
                List.of(List.of("id", "0")), List.of(List.of("id", "-1")), List.of(List.of("id", "1.1")),
                List.of(List.of("id", "100"), List.of(" id ", "200")), List.of(List.of("id", "not-a-price")))) {
            assertThat(controller.startUpdateCustodialPrices(excel(rows), "fixture").getSuccess()).isFalse();
        }
        assertThat(controller.startUpdateCustodialPrices(null, "fixture").getSuccess()).isFalse();
        assertThat(controller.startUpdateCustodialPrices(excel(List.of(List.of("id", "100"))), " ").getSuccess()).isFalse();
        assertThat(controller.startUpdateCustodialPrices(new MockMultipartFile("file", "file.csv", "text/csv",
                new byte[]{1}), "fixture").getSuccess()).isFalse();
        assertThat(controller.startUpdateCustodialPrices(new MockMultipartFile("file", "file.xlsx", "",
                new byte[10 * 1024 * 1024 + 1]), "fixture").getSuccess()).isFalse();
        verifyNoInteractions(manager);
    }

    private MockMultipartFile excel(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(List.of(List.of("listingId"), List.of("价格"))).sheet().doWrite(rows);
        return new MockMultipartFile("file", "prices.xlsx", "application/octet-stream", out.toByteArray());
    }
}
