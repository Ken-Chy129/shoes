package cn.ken.shoes.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceDownTypeTest {

    @Test
    void parsesExcelValuesAndDefaultsBlankCells() {
        assertThat(PriceDownType.fromExcelValue(null)).isEqualTo(PriceDownType.DEFAULT);
        assertThat(PriceDownType.fromExcelValue(" ")).isEqualTo(PriceDownType.DEFAULT);
        assertThat(PriceDownType.fromExcelValue("默认")).isEqualTo(PriceDownType.DEFAULT);
        assertThat(PriceDownType.fromExcelValue("POISON")).isEqualTo(PriceDownType.POISON);
        assertThat(PriceDownType.fromExcelValue("得物")).isEqualTo(PriceDownType.POISON);
        assertThat(PriceDownType.fromExcelValue("poison_35")).isEqualTo(PriceDownType.POISON_35);
        assertThat(PriceDownType.fromExcelValue("得物3.5")).isEqualTo(PriceDownType.POISON_35);
    }

    @Test
    void rejectsUnknownExcelValues() {
        assertThatThrownBy(() -> PriceDownType.fromExcelValue("最低价"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("压价类型");
    }
}
