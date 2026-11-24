package cn.ken.shoes.util;

import cn.ken.shoes.model.entity.SizeChartDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 线上 Puma 尺码表真实行：同一美码在男款行、女款行对应的欧码不同。 */
class SizeConvertUtilTest {

    @BeforeEach
    void setUp() {
        // 女款行放前面，确保结果不依赖行的顺序；女款行 us_size 填的是男码
        SizeConvertUtil.initCache(List.of(
                row("WOMENS", "40.5", "8.5", "9.5"),
                row("WOMENS", "39", "7.5", "8.5"),
                row("WOMENS", "36", "5", "6"),
                row("MENS", "41", "8.5", "10"),
                row("MENS", "39", "7", "8.5"),
                row("MENS", "37", "5", "6.5")));
    }

    @Test
    void ebayMensSizeUsesMensRowsByDefault() {
        assertThat(SizeConvertUtil.getKcEuSizeFromUs("PUMA", "USM", "8.5")).isEqualTo("41");
        assertThat(SizeConvertUtil.getKcEuSizeFromUs("PUMA", "USM", "5")).isEqualTo("37");
    }

    @Test
    void ebayUsesProductGenderRowsWhenKnown() {
        assertThat(SizeConvertUtil.getKcEuSizeFromUs("PUMA", "USM", "8.5", "Women")).isEqualTo("40.5");
        assertThat(SizeConvertUtil.getKcEuSizeFromUs("PUMA", "USM", "8.5", "Men")).isEqualTo("41");
        assertThat(SizeConvertUtil.getKcEuSizeFromUs("PUMA", "USW", "8.5")).isEqualTo("39");
    }

    @Test
    void stockXWomensSizeUsesWomensColumn() {
        assertThat(SizeConvertUtil.getStockXEuSize("Puma", "8.5")).isEqualTo("41");
        assertThat(SizeConvertUtil.getStockXEuSize("Puma", "5")).isEqualTo("37");
        assertThat(SizeConvertUtil.getStockXEuSize("Puma", "8.5W")).isEqualTo("39");
        assertThat(SizeConvertUtil.getStockXEuSize("Puma", "6W")).isEqualTo("36");
    }

    private static SizeChartDO row(String gender, String eu, String menUs, String womenUs) {
        SizeChartDO chart = new SizeChartDO();
        chart.setBrand("PUMA");
        chart.setStockxBrand("Puma");
        chart.setGender(gender);
        chart.setEuSize(eu);
        chart.setUsSize(menUs);
        chart.setMenUSSize(menUs);
        chart.setWomenUSSize(womenUs);
        return chart;
    }
}
