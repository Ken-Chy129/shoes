package cn.ken.shoes;

import cn.ken.shoes.common.PriceDownType;
import cn.ken.shoes.model.excel.StockXPriceDownInputExcel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShoesContextPriceDownTypeTest {

    private static final String ACCOUNT_ID = "price-down-type-test";

    @AfterEach
    void cleanUp() {
        ShoesContext.getPriceDownMap(ACCOUNT_ID, "STANDARD").clear();
    }

    @Test
    void loadsExplicitAndBlankExcelTypes() {
        StockXPriceDownInputExcel defaultItem = item("DEFAULT-SKU", "42", 100, null);
        StockXPriceDownInputExcel poisonItem = item("POISON-SKU", "43", 120, "得物");
        StockXPriceDownInputExcel poison35Item = item("POISON35-SKU", "44", 130, "得物3.5");

        ShoesContext.loadPriceDownExcel(ACCOUNT_ID, "STANDARD",
                List.of(defaultItem, poisonItem, poison35Item));

        assertThat(ShoesContext.getPriceDownConfig(ACCOUNT_ID, "STANDARD", "DEFAULT-SKU", "42").type())
                .isEqualTo(PriceDownType.DEFAULT);
        assertThat(ShoesContext.getPriceDownConfig(ACCOUNT_ID, "STANDARD", "POISON-SKU", "43").type())
                .isEqualTo(PriceDownType.POISON);
        assertThat(ShoesContext.getPriceDownConfig(ACCOUNT_ID, "STANDARD", "POISON35-SKU", "44").type())
                .isEqualTo(PriceDownType.POISON_35);
    }

    @Test
    void rejectsInvalidTypeBeforeReplacingExistingInput() {
        ShoesContext.getPriceDownMap(ACCOUNT_ID, "STANDARD")
                .put("EXISTING:42", new ShoesContext.PriceDownConfig(99, false));

        assertThatThrownBy(() -> ShoesContext.loadPriceDownExcel(ACCOUNT_ID, "STANDARD",
                List.of(item("BAD-SKU", "43", 100, "最低价"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("压价类型");

        assertThat(ShoesContext.getPriceDownMap(ACCOUNT_ID, "STANDARD"))
                .containsOnlyKeys("EXISTING:42");
    }

    private static StockXPriceDownInputExcel item(String styleId, String size, Integer minPrice, String type) {
        StockXPriceDownInputExcel item = new StockXPriceDownInputExcel();
        item.setStyleId(styleId);
        item.setSize(size);
        item.setMinPrice(minPrice);
        item.setPriceDownType(type);
        return item;
    }
}
