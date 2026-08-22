package cn.ken.shoes.client;

import cn.ken.shoes.common.StockXPurchaseOperation;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
