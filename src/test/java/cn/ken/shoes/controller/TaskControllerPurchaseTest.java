package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.StockXBidDeleteInputExcel;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import cn.ken.shoes.model.excel.StockXBidUpdateInputExcel;
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
import static org.mockito.ArgumentMatchers.eq;

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
    void startsDeleteAllBidsOnTheJsonEndpoint() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startPurchase("account-a", StockXPurchaseOperation.DELETE_BIDS)).thenReturn(106L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startPurchase(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("operation", "delete_bids"));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("106");
        verify(manager).startPurchase("account-a", StockXPurchaseOperation.DELETE_BIDS);
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
    void rejectsUpdateBidsOnTheReadOnlyJsonEndpoint() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startPurchase(new JSONObject(true)
                .fluentPut("accountId", "account-a")
                .fluentPut("operation", "update_bids"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Excel上传接口");
        verifyNoInteractions(manager);
    }

    @Test
    void startsDeleteBidsForNormalizedUniqueStyleIdsFromExcel() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startDeleteBids(eq("account-a"), argThat(rows ->
                rows.size() == 2
                        && "STYLE-1".equals(rows.get(0).getStyleId())
                        && "STYLE-2".equals(rows.get(1).getStyleId()))))
                .thenReturn(108L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startDeleteBids(
                deleteBidsExcelFile("styles.xlsx", List.of(
                        deleteBid(" style-1 "),
                        deleteBid("STYLE-1"),
                        deleteBid(" "),
                        deleteBid("style-2"))),
                "account-a");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("108");
        verify(manager).startDeleteBids(eq("account-a"), argThat(rows -> rows.size() == 2));
    }

    @Test
    void rejectsMissingOrEmptyDeleteBidsExcel() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);
        MockMultipartFile empty = new MockMultipartFile("file", "styles.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        Result<String> result = controller.startDeleteBids(empty, "account-a");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("请上传货号Excel");
        verifyNoInteractions(manager);
    }

    @Test
    void startsUpdateBidsFromValidatedExcelRows() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startUpdateBids(org.mockito.ArgumentMatchers.eq("account-a"), argThat(rows ->
                rows.size() == 1 && "bid-123".equals(rows.get(0).getBidId())
                        && rows.get(0).getPrice().compareTo(new BigDecimal("77")) == 0),
                org.mockito.ArgumentMatchers.eq(300L)))
                .thenReturn(107L);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startUpdateBids(
                updateExcelFile("updates.xlsx", List.of(update(" bid-123 ", "77"))), "account-a", 300L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("107");
    }

    @Test
    void rejectsDuplicateBidIdsInUpdateExcel() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startUpdateBids(updateExcelFile("updates.xlsx", List.of(
                update("BID-1", "77"), update("bid-1", "78"))), "account-a", 300L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("出价ID重复");
        verifyNoInteractions(manager);
    }

    @Test
    void rejectsUnsafeUpdateBidPollingIntervals() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = new TaskController();
        setField(controller, "taskExecutorManager", manager);

        Result<String> result = controller.startUpdateBids(
                updateExcelFile("updates.xlsx", List.of(update("bid-1", "77"))), "account-a", 30L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("60到86400秒");
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

    private static StockXBidUpdateInputExcel update(String bidId, String price) {
        StockXBidUpdateInputExcel row = new StockXBidUpdateInputExcel();
        row.setBidId(bidId);
        row.setPrice(new BigDecimal(price));
        return row;
    }

    private static MockMultipartFile updateExcelFile(String fileName,
                                                     List<StockXBidUpdateInputExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, StockXBidUpdateInputExcel.class).sheet().doWrite(rows);
        return new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private static StockXBidDeleteInputExcel deleteBid(String styleId) {
        StockXBidDeleteInputExcel row = new StockXBidDeleteInputExcel();
        row.setStyleId(styleId);
        return row;
    }

    private static MockMultipartFile deleteBidsExcelFile(
            String fileName, List<StockXBidDeleteInputExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, StockXBidDeleteInputExcel.class).sheet().doWrite(rows);
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
