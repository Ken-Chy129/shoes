package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.excel.StockXPriceDownInputExcel;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskControllerPriceDownUploadTest {

    private static final String ACCOUNT_ID = "running-upload-account";
    private static final String INVENTORY_TYPE = "STANDARD";

    @AfterEach
    void cleanUp() {
        ShoesContext.getPriceDownMap(ACCOUNT_ID, INVENTORY_TYPE).clear();
        TaskSwitch.clearExcelState(ACCOUNT_ID, INVENTORY_TYPE);
        StockXConfig.setAccounts(List.of());
    }

    @Test
    void uploadCanPrepareTheNextExcelTaskWhileFullScanIsRunning() throws Exception {
        StockXAccount account = new StockXAccount();
        account.setName(ACCOUNT_ID);
        StockXConfig.setAccounts(List.of(account));
        Map<String, ShoesContext.PriceDownConfig> runningInput = Map.of(
                "RUNNING:42", new ShoesContext.PriceDownConfig(100, false));
        TaskSwitch.setPriceDownInput(ACCOUNT_ID, INVENTORY_TYPE, ListingFetchMode.ALL, runningInput);
        TaskSwitch.setExcelRunning(ACCOUNT_ID, INVENTORY_TYPE, ListingFetchMode.ALL, true);

        TaskController controller = new TaskController();
        setField(controller, "configManager", new ConfigManager() {
            @Override
            public void savePriceDownExcel(String accountId, String inventoryType) {
                // no-op: this test only verifies upload/runtime isolation
            }
        });
        StockXPriceDownInputExcel row = new StockXPriceDownInputExcel();
        row.setStyleId("NEXT");
        row.setSize("43");
        row.setMinPrice(120);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, StockXPriceDownInputExcel.class).sheet().doWrite(List.of(row));
        MockMultipartFile file = new MockMultipartFile(
                "file", "pricedown.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());

        Result<Integer> result = controller.uploadPriceDownExcel(file, ACCOUNT_ID, INVENTORY_TYPE);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(1);
        assertThat(ShoesContext.getPriceDownMap(ACCOUNT_ID, INVENTORY_TYPE))
                .containsOnlyKeys("NEXT:43");
        assertThat(TaskSwitch.getPriceDownInput(
                ACCOUNT_ID, INVENTORY_TYPE, ListingFetchMode.ALL))
                .containsOnlyKeys("RUNNING:42");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
