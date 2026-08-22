package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

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

    @Test
    void rejectsCreateBidsOnTheReadOnlyJsonEndpoint() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startPurchase(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("operation", "create_bids"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Excel上传接口");
        verifyNoInteractions(manager);
    }

    @Test
    void startsCreateBidsFromValidatedExcelRows() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startCreateBids(org.mockito.ArgumentMatchers.eq("account-a"), argThat(rows ->
                rows.size() == 1
                        && "100289469".equals(rows.get(0).getStyleId())
                        && "US M 4.5".equals(rows.get(0).getSize())
                        && rows.get(0).getPrice().compareTo(BigDecimal.ONE) == 0)))
                .thenReturn(106L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startCreateBids(
                excelFile("bids.xlsx", List.of(bid("100289469", "US M 4.5", "1"))),
                "account-a");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("106");
    }

    @Test
    void rejectsInvalidBidExcelBeforeStartingATask() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startCreateBids(
                excelFile("bids.xlsx", List.of(bid("STYLE-1", "US 9", "1.5"))),
                "account-a");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("整数美元");
        verifyNoInteractions(manager);
    }

    private static StockXBidInputExcel bid(String styleId, String size, String price) {
        StockXBidInputExcel row = new StockXBidInputExcel();
        row.setStyleId(styleId);
        row.setSize(size);
        row.setPrice(new BigDecimal(price));
        return row;
    }

    private static MockMultipartFile excelFile(String fileName, List<StockXBidInputExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, StockXBidInputExcel.class).sheet().doWrite(rows);
        return new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
