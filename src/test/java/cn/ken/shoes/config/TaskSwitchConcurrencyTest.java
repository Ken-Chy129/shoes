package cn.ken.shoes.config;

import cn.ken.shoes.common.ListingFetchMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSwitchConcurrencyTest {

    @Test
    void onlyOneCallerCanAcquireTheSameExcelTaskKey() {
        String account = "atomic-account";
        String inventoryType = "STANDARD";
        TaskSwitch.clearExcelState(account, inventoryType);

        try {
            assertThat(TaskSwitch.tryStartExcel(account, inventoryType)).isTrue();
            assertThat(TaskSwitch.tryStartExcel(account, inventoryType)).isFalse();
        } finally {
            TaskSwitch.clearExcelState(account, inventoryType);
        }
    }

    @Test
    void fullScanAndExcelSearchUseIndependentPriceDownChannels() {
        String account = "parallel-price-account";
        String inventoryType = "STANDARD";
        TaskSwitch.clearExcelState(account, inventoryType, ListingFetchMode.ALL);
        TaskSwitch.clearExcelState(account, inventoryType, ListingFetchMode.EXCEL_SEARCH);

        try {
            assertThat(TaskSwitch.tryStartExcel(account, inventoryType, ListingFetchMode.EXCEL_SEARCH)).isTrue();
            assertThat(TaskSwitch.tryStartExcel(account, inventoryType, ListingFetchMode.ALL)).isTrue();
            assertThat(TaskSwitch.tryStartExcel(account, inventoryType, ListingFetchMode.EXCEL_SEARCH)).isFalse();
            assertThat(TaskSwitch.tryStartExcel(account, inventoryType, ListingFetchMode.ALL)).isFalse();
        } finally {
            TaskSwitch.clearExcelState(account, inventoryType, ListingFetchMode.ALL);
            TaskSwitch.clearExcelState(account, inventoryType, ListingFetchMode.EXCEL_SEARCH);
        }
    }
}
