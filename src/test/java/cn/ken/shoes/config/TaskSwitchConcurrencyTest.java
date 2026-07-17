package cn.ken.shoes.config;

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
}
