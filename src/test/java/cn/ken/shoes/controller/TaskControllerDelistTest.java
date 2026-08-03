package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.DelistMode;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskControllerDelistTest {

    private static final String UPLOAD_ACCOUNT = "delist-style-size-upload-account";

    @AfterEach
    void cleanUp() {
        ShoesContext.loadDelistExcel(UPLOAD_ACCOUNT, "STANDARD", List.of());
        StockXConfig.setAccounts(List.of());
    }

    @Test
    void acceptsExcelContainingOnlyStyleIdAndSizeColumns() throws Exception {
        StockXAccount account = new StockXAccount();
        account.setName(UPLOAD_ACCOUNT);
        StockXConfig.setAccounts(List.of(account));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output)
                .head(List.of(List.of("货号"), List.of("尺码")))
                .sheet()
                .doWrite(List.of(List.of("DZ5485-612", "US 9.5")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "delist-by-style-size.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
        TaskController controller = new TaskController();
        setField(controller, "configManager", new ConfigManager() {
            @Override
            public void saveDelistExcel(String accountId, String inventoryType) {
                // no-op: this test only verifies parsing and runtime storage
            }
        });

        Result<Integer> result = controller.uploadDelistExcel(file, UPLOAD_ACCOUNT, "STANDARD");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(1);
        assertThat(ShoesContext.getDelistList(UPLOAD_ACCOUNT, "STANDARD"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getListingId()).isBlank();
                    assertThat(row.getStyleId()).isEqualTo("DZ5485-612");
                    assertThat(row.getSize()).isEqualTo("US 9.5");
                });
    }

    @Test
    void rejectsStyleIdExcelRowWithoutSize() throws Exception {
        StockXAccount account = new StockXAccount();
        account.setName(UPLOAD_ACCOUNT);
        StockXConfig.setAccounts(List.of(account));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output)
                .head(List.of(List.of("货号")))
                .sheet()
                .doWrite(List.of(List.of("DZ5485-612")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "invalid-delist.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());

        Result<Integer> result = new TaskController().uploadDelistExcel(
                file, UPLOAD_ACCOUNT, "STANDARD");

        assertThat(result.getSuccess()).isFalse();
        assertThat(ShoesContext.getDelistList(UPLOAD_ACCOUNT, "STANDARD")).isEmpty();
    }

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
