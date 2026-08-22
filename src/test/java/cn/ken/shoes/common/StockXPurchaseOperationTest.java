package cn.ken.shoes.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXPurchaseOperationTest {

    @Test
    void resolvesAllPublicOperationCodes() {
        assertThat(StockXPurchaseOperation.fromCode("bids")).isEqualTo(StockXPurchaseOperation.BIDS);
        assertThat(StockXPurchaseOperation.fromCode("orders")).isEqualTo(StockXPurchaseOperation.ORDERS);
        assertThat(StockXPurchaseOperation.fromCode("history")).isEqualTo(StockXPurchaseOperation.HISTORY);
        assertThat(StockXPurchaseOperation.fromCode("create_bids"))
                .isEqualTo(StockXPurchaseOperation.CREATE_BIDS);
        assertThat(StockXPurchaseOperation.fromCode("update_bids"))
                .isEqualTo(StockXPurchaseOperation.UPDATE_BIDS);
    }

    @Test
    void rejectsBlankAndUnknownOperationCodes() {
        assertThat(StockXPurchaseOperation.fromCode(null)).isNull();
        assertThat(StockXPurchaseOperation.fromCode("  ")).isNull();
        assertThat(StockXPurchaseOperation.fromCode("checkout")).isNull();
    }
}
