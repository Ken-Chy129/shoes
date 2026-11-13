package cn.ken.shoes.config;

import cn.ken.shoes.common.ListingFetchMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXCustodialPriceLockTest {
    @Test
    void excludesBothAutomaticPriceModesButNotOtherAccountsOrStandardInventory() {
        String account = "custodial-lock-test";
        try {
            assertThat(TaskSwitch.tryStartCustodialPriceUpdate(account)).isTrue();
            assertThat(TaskSwitch.tryStartCustodialPriceUpdate(account)).isFalse();
            for (ListingFetchMode mode : ListingFetchMode.values()) {
                assertThat(TaskSwitch.tryStartExcel(account, "CUSTODIAL", mode)).isFalse();
            }
            assertThat(TaskSwitch.tryStartExcel(account, "STANDARD")).isTrue();
            TaskSwitch.cancelCustodialPriceUpdate(account);
            assertThat(TaskSwitch.isCustodialPriceUpdateCancelled(account)).isTrue();
            assertThat(TaskSwitch.tryStartExcel(account, "CUSTODIAL")).isFalse();
            TaskSwitch.clearCustodialPriceUpdate(account);
            for (ListingFetchMode mode : ListingFetchMode.values()) {
                assertThat(TaskSwitch.tryStartExcel(account, "CUSTODIAL", mode)).isTrue();
                assertThat(TaskSwitch.tryStartCustodialPriceUpdate(account)).isFalse();
                TaskSwitch.clearExcelState(account, "CUSTODIAL", mode);
            }
        } finally {
            TaskSwitch.clearCustodialPriceUpdate(account);
            TaskSwitch.clearExcelState(account, "CUSTODIAL");
            TaskSwitch.clearExcelState(account, "STANDARD");
        }
    }
}
