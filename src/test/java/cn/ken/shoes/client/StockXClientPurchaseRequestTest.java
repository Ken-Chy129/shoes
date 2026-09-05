package cn.ken.shoes.client;

import cn.ken.shoes.common.StockXPurchaseOperation;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StockXClientPurchaseRequestTest {

    @Test
    void buildsCurrentBidsRequestObservedOnStockXPro() {
        JSONObject request = StockXClient.buildPurchaseRequest(
                StockXPurchaseOperation.BIDS, null, "US");

        assertThat(request.getString("operationName")).isEqualTo("Bids");
        JSONObject variables = request.getJSONObject("variables");
        assertThat(variables.getInteger("first")).isEqualTo(50);
        assertThat(variables.getString("sort")).isEqualTo("UPDATED_AT");
        assertThat(variables.getString("order")).isEqualTo("DESC");
        assertThat(variables.getString("state")).isEqualTo("CURRENT");
        assertThat(variables.getString("after")).isEmpty();
        assertThat(variables.getString("country")).isEqualTo("US");
        assertThat(variables.getString("market")).isEqualTo("US");
        assertThat(variables.getJSONObject("filters")
                .getJSONObject("listingType").getJSONArray("in").toJavaList(String.class))
                .containsExactlyElementsOf(List.of("STANDARD", "NORMAL"));
        assertThat(hash(request)).isEqualTo(
                "da212069375e2bfd5e9aca755cf773d65b836c94e98f0a6a49347f89c0fc56a2");
    }

    @Test
    void buildsPendingPurchaseOrdersRequestObservedOnStockXPro() {
        JSONObject request = StockXClient.buildPurchaseRequest(
                StockXPurchaseOperation.ORDERS, "next-cursor", "US");

        assertBuyingRequest(request, "PENDING", "next-cursor");
    }

    @Test
    void buildsHistoricalPurchasesRequestObservedOnStockXPro() {
        JSONObject request = StockXClient.buildPurchaseRequest(
                StockXPurchaseOperation.HISTORY, null, "US");

        assertBuyingRequest(request, "HISTORICAL", "");
    }

    @Test
    void rejectsDeleteBidsOnThePersistedReadRequestBuilder() {
        assertThat(catchThrowable(() -> StockXClient.buildPurchaseRequest(
                StockXPurchaseOperation.DELETE_BIDS, null, "US")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("独立写接口");
    }

    @Test
    void buildsBatchBidMarketLevelsRequest() {
        JSONObject request = StockXClient.buildBidMarketDataRequest(
                List.of("variant-a", "variant-b"), "US");

        assertThat(request.getString("operationName")).isEqualTo("BidMarketLevels");
        assertThat(request.getJSONObject("variables"))
                .containsEntry("market", "US")
                .containsEntry("id0", "variant-a")
                .containsEntry("id1", "variant-b");
        assertThat(request.getString("query"))
                .contains("v0:variant(id:$id0)")
                .contains("v1:variant(id:$id1)")
                .contains("priceLevels(transactionType:BID,page:1,limit:2,market:$market)")
                .contains("standard{lowest{amount}}")
                .contains("expressStandard{lowest{amount}}");
    }

    @Test
    void buildsMaximumBidMarketBatchWithinRequestSizeBudget() {
        List<String> variantIds = IntStream.range(0, 50)
                .mapToObj(index -> String.format("01234567-89ab-cdef-0123-%012d", index))
                .toList();

        JSONObject request = StockXClient.buildBidMarketDataRequest(variantIds, "US");
        int requestBytes = request.toJSONString().getBytes(StandardCharsets.UTF_8).length;

        assertThat(request.getJSONObject("variables")).hasSize(51);
        assertThat(request.getString("query")).contains("v49:variant(id:$id49)");
        assertThat(requestBytes).isLessThan(32 * 1024);
    }

    @Test
    void rejectsBidMarketBatchLargerThanFiftyVariants() {
        List<String> variantIds = IntStream.range(0, 51)
                .mapToObj(index -> "variant-" + index)
                .toList();

        assertThat(catchThrowable(() -> StockXClient.buildBidMarketDataRequest(variantIds, "US")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到50");
    }

    private static void assertBuyingRequest(JSONObject request, String state, String after) {
        assertThat(request.getString("operationName")).isEqualTo("Buying");
        JSONObject variables = request.getJSONObject("variables");
        assertThat(variables.getInteger("first")).isEqualTo(50);
        assertThat(variables.getString("sort")).isEqualTo("MATCHED_AT");
        assertThat(variables.getString("order")).isEqualTo("DESC");
        assertThat(variables.getString("state")).isEqualTo(state);
        assertThat(variables.getString("after")).isEqualTo(after);
        assertThat(hash(request)).isEqualTo(
                "e6da13338345ed277de50220547d8dd5de59e78a8b4cbf3d73ee6d6f25b3d76a");
    }

    private static String hash(JSONObject request) {
        return request.getJSONObject("extensions")
                .getJSONObject("persistedQuery")
                .getString("sha256Hash");
    }
}
