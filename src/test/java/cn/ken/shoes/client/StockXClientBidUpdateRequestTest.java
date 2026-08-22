package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXBidUpdateItem;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StockXClientBidUpdateRequestTest {

    @Test
    void buildsBulkUpdateBidsMutationObservedOnStockXPro() {
        Instant before = Instant.now().plus(Duration.ofDays(364));

        JSONObject request = StockXClient.buildUpdateBidsRequest(List.of(
                new StockXBidUpdateItem("bid-1", new BigDecimal("2"),
                        "HOME_DELIVERY", "USD", null),
                new StockXBidUpdateItem("bid-2", new BigDecimal("125"),
                        "FLEX", "USD", "STANDARD")
        ));

        assertThat(request.getString("operationName")).isEqualTo("BulkUpdateBids");
        assertThat(request.getString("query"))
                .contains("mutation BulkUpdateBids($input: [UpdateBidInput!])")
                .contains("updateBids(input: $input)")
                .contains("id")
                .contains("status");

        JSONArray input = request.getJSONObject("variables").getJSONArray("input");
        assertThat(input).hasSize(2);
        assertThat(input.getJSONObject(0))
                .containsEntry("id", "bid-1")
                .containsEntry("deliveryOptionType", "HOME_DELIVERY")
                .containsEntry("currency", "USD")
                .doesNotContainKey("checkoutType");
        assertThat(input.getJSONObject(0).getBigDecimal("amount")).isEqualByComparingTo("2");
        assertThat(Instant.parse(input.getJSONObject(0).getString("expires")))
                .isAfter(before)
                .isBefore(Instant.now().plus(Duration.ofDays(366)));
        assertThat(input.getJSONObject(1)).containsEntry("checkoutType", "STANDARD");
    }

    @Test
    void rejectsEmptyOrOversizedBidUpdateBatches() {
        assertThat(catchThrowable(() -> StockXClient.buildUpdateBidsRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到50");

        List<StockXBidUpdateItem> oversized = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(i -> new StockXBidUpdateItem("bid-" + i, BigDecimal.ONE,
                        "HOME_DELIVERY", "USD", null))
                .toList();
        assertThat(catchThrowable(() -> StockXClient.buildUpdateBidsRequest(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到50");
    }
}
