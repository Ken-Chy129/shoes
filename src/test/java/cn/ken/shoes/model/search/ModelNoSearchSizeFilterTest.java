package cn.ken.shoes.model.search;

import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelNoSearchSizeFilterTest {

    @Test
    void restrictsModelToSpecifiedUsOrEuSizes() {
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(
                row("STYLE-1", "9.5"),
                row("STYLE-1", "42")
        ));

        assertThat(ModelNoSearchSizeFilter.matches(filters, "style-1", "9.5", "41")).isTrue();
        assertThat(ModelNoSearchSizeFilter.matches(filters, "STYLE-1", "10", "42")).isTrue();
        assertThat(ModelNoSearchSizeFilter.matches(filters, "STYLE-1", "10", "43")).isFalse();
    }

    @Test
    void leavesModelUnrestrictedWhenSizeIsBlank() {
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(
                row("STYLE-2", null)
        ));

        assertThat(filters).doesNotContainKey("STYLE-2");
        assertThat(ModelNoSearchSizeFilter.matches(filters, "STYLE-2", "12", "46")).isTrue();
    }

    @Test
    void blankSizeMakesModelUnrestrictedEvenWhenSpecificRowsExist() {
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(
                row("STYLE-3", "9"),
                row("style-3", " "),
                row("STYLE-3", "10")
        ));

        assertThat(filters).doesNotContainKey("STYLE-3");
        assertThat(ModelNoSearchSizeFilter.matches(filters, "STYLE-3", "11", "45")).isTrue();
    }

    @Test
    void normalizesSizePrefixesWhitespaceAndTrailingZero() {
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(
                row("STYLE-4", " US 9.0 "),
                row("STYLE-4", "EU 42.0")
        ));

        assertThat(ModelNoSearchSizeFilter.matches(filters, "STYLE-4", "9", "41")).isTrue();
        assertThat(ModelNoSearchSizeFilter.matches(filters, "STYLE-4", "10", "42")).isTrue();
    }

    @Test
    void matchesWomenSizeOnlyAgainstStockXWomenScale() {
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(
                row("1182A678-001", "9.5W")
        ));

        assertThat(ModelNoSearchSizeFilter.matches(
                filters, "1182A678-001", "8", "9.5", "41.5")).isTrue();
        assertThat(ModelNoSearchSizeFilter.matches(
                filters, "1182A678-001", "9.5", "11", "42.5")).isFalse();
    }

    @Test
    void acceptsStockXWomenSizeAliases() {
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(
                row("STYLE-W", "US W 6.0"),
                row("STYLE-W", "6.5 W")
        ));

        assertThat(ModelNoSearchSizeFilter.matches(
                filters, "STYLE-W", "4.5", "6", "37.5")).isTrue();
        assertThat(ModelNoSearchSizeFilter.matches(
                filters, "STYLE-W", "5", "6.5", "38")).isTrue();
    }

    private static ModelNoSearchExcel row(String modelNo, String size) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        row.setSize(size);
        return row;
    }
}
