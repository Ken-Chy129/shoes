package cn.ken.shoes.order;

import cn.ken.shoes.common.StockXOrderCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockXOrderCategoryTest {

    @Test
    void exposesTheCapturedGraphqlFiltersForEachSelectableOrderType() {
        assertThat(StockXOrderCategory.fromCode("completed"))
                .hasValueSatisfying(category -> {
                    assertThat(category.getListingStatuses()).contains("COMPLETED", "RETURN_COMPLETED");
                    assertThat(category.getOrderStatuses()).isEmpty();
                });
        assertThat(StockXOrderCategory.fromCode("cancelled"))
                .hasValueSatisfying(category -> {
                    assertThat(category.getListingStatuses()).containsExactly("CANCELED");
                    assertThat(category.getOrderStatuses()).isEmpty();
                });
        assertThat(StockXOrderCategory.fromCode("pending_payout"))
                .hasValueSatisfying(category -> {
                    assertThat(category.getListingStatuses()).containsExactly("MATCHED");
                    assertThat(category.getOrderStatuses()).isEqualTo(List.of(
                            "PAYOUTPENDING", "PAYOUTCOMPLETED", "PAYOUTFAILED"));
                });
    }
}
