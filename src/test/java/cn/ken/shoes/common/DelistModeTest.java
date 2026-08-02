package cn.ken.shoes.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelistModeTest {

    @Test
    void parsesExcelAndFullDelistModes() {
        assertThat(DelistMode.fromCode("excel")).isEqualTo(DelistMode.EXCEL);
        assertThat(DelistMode.fromCode("ALL")).isEqualTo(DelistMode.ALL);
        assertThat(DelistMode.fromCode("unknown")).isNull();
    }
}
