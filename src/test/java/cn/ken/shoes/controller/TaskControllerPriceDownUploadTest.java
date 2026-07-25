package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

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
    void rejectsUploadWithoutChangingRulesWhenTaskIsRunning() throws Exception {
        StockXAccount account = new StockXAccount();
        account.setName(ACCOUNT_ID);
        StockXConfig.setAccounts(List.of(account));
        ShoesContext.getPriceDownMap(ACCOUNT_ID, INVENTORY_TYPE)
                .put("EXISTING:42", new ShoesContext.PriceDownConfig(100, false));
        TaskSwitch.setExcelRunning(ACCOUNT_ID, INVENTORY_TYPE, true);

        TaskController controller = new TaskController();
        MockMultipartFile file = new MockMultipartFile(
                "file", "pricedown.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3});

        Result<Integer> result = controller.uploadPriceDownExcel(file, ACCOUNT_ID, INVENTORY_TYPE);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("正在运行");
        assertThat(ShoesContext.getPriceDownMap(ACCOUNT_ID, INVENTORY_TYPE))
                .containsOnlyKeys("EXISTING:42");
    }
}
