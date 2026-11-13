package cn.ken.shoes.client;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXClientShipmentRequestTest {

    @Test
    void rejectsPartialGraphqlResultsWithoutExposingUpstreamErrorDetails() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryPro(String body, Headers headers, String accountName) {
                return JSONObject.parseObject("""
                        {"errors":[{"message":"upstream-private-detail"}],
                         "data":{"viewer":{"sellerListings":{
                           "edges":[],"pageInfo":{"hasNextPage":false}
                         }}}}
                        """);
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("test");
        account.setAuthorization("test-only");

        assertThatThrownBy(() -> client.queryOrderListings(
                StockXOrderCategory.SHIPMENT_PACKED, 1, account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("StockX货运查询返回错误，请稍后重试");
    }

    @Test
    void usesStockxProFiltersForAllThreeShipmentTabs() {
        assertFilters(StockXOrderCategory.SHIPMENT_PACKED,
                List.of("READY_TO_SHIP"), List.of("CREATED"));
        assertFilters(StockXOrderCategory.SHIPMENT_SHIPPED,
                List.of("MATCHED", "READY_TO_SHIP", "IN_TRANSIT_TO_WAREHOUSE"), List.of("SHIPPED"));
        assertFilters(StockXOrderCategory.SHIPMENT_RECEIVED,
                List.of("MATCHED", "READY_TO_SHIP"),
                List.of("RECEIVED", "AUTHENTICATING", "AUTHENTICATED", "AUTHFAILED"));
    }

    private void assertFilters(StockXOrderCategory category, List<String> listing, List<String> order) {
        JSONObject request = StockXClient.buildOrderListingsRequest(category, 2, "HK");
        JSONObject variables = request.getJSONObject("variables");
        JSONObject filters = variables.getJSONObject("filters");
        assertThat(category.isShipment()).isTrue();
        assertThat(filters.getJSONObject("listingStatus").getJSONArray("in").toJavaList(String.class))
                .isEqualTo(listing);
        assertThat(filters.getJSONObject("orderStatus").getJSONArray("in").toJavaList(String.class))
                .isEqualTo(order);
        assertThat(filters.getJSONObject("inventoryType").getJSONArray("in").toJavaList(String.class))
                .containsExactly("STANDARD");
        assertThat(filters.getJSONObject("optionalVerification").getBoolean("is")).isTrue();
        assertThat(variables.getInteger("pageNumber")).isEqualTo(2);
        assertThat(variables.getInteger("pageSize")).isEqualTo(50);
        assertThat(variables.getString("country")).isEqualTo("HK");
        assertThat(variables.getString("market")).isEqualTo("HK");
        assertThat(variables.getString("sort")).isEqualTo("MATCHED_AT");
        assertThat(variables.getString("order")).isEqualTo("DESC");
        assertThat(request.getString("query"))
                .contains("sellerListings(", "displayId", "trackingNumber", "shipByDate");
        assertThat(request.containsKey("extensions")).isFalse();
    }
}
