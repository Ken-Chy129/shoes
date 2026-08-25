package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXBidCreateItem;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientBidRequestTest {

    @Test
    void buildsBulkCreateBidsMutationObservedOnStockXPro() {
        JSONObject request = StockXClient.buildCreateBidsRequest(List.of(
                new StockXBidCreateItem("variant-1", new BigDecimal("1"), "us m"),
                new StockXBidCreateItem("variant-2", new BigDecimal("125"), "eu")
        ));

        assertThat(request.getString("operationName")).isEqualTo("BulkCreateBids");
        assertThat(request.getString("query"))
                .contains("mutation BulkCreateBids($input: [CreateBidInput!])")
                .contains("createBids(input: $input)")
                .contains("id")
                .contains("status");

        JSONArray input = request.getJSONObject("variables").getJSONArray("input");
        assertThat(input).hasSize(2);
        assertThat(input.getJSONObject(0))
                .containsEntry("variantId", "variant-1")
                .containsEntry("context", "BID")
                .containsEntry("currency", "USD")
                .containsEntry("expiresIn", 365)
                .containsEntry("deliveryOptionType", "BUY_INTO_FLEX")
                .containsEntry("localizedSizeType", "us m");
        assertThat(input.getJSONObject(0).getBigDecimal("amount")).isEqualByComparingTo("1");
        assertThat(input.getJSONObject(1).getBigDecimal("amount")).isEqualByComparingTo("125");
    }

    @Test
    void rejectsEmptyOrOversizedBidBatchesBeforeCallingStockX() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> StockXClient.buildCreateBidsRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到100");

        List<StockXBidCreateItem> oversized = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(i -> new StockXBidCreateItem("variant-" + i, BigDecimal.ONE, "us m"))
                .toList();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> StockXClient.buildCreateBidsRequest(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到100");
    }
}
