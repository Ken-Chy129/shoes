package cn.ken.shoes.client;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientPriceUpdateRequestTest {

    @Test
    void buildsCapturedUpdateSellerListingRequest() {
        JSONObject request = StockXClient.buildUpdateSellerListingRequest(
                Map.of(
                        "listingId", "listing-123",
                        "amount", "301",
                        "currencyCode", "USD"),
                "2027-07-17T07:33:10+00:00",
                "trace-456");

        assertThat(request.getString("operationName")).isEqualTo("UpdateSellerListing");
        JSONObject variables = request.getJSONObject("variables");
        assertThat(variables.getString("id")).isEqualTo("listing-123");
        assertThat(variables.getString("amount")).isEqualTo("301");
        assertThat(variables.getString("currency")).isEqualTo("USD");
        assertThat(variables.getString("expiresAt")).isEqualTo("2027-07-17T07:33:10+00:00");
        assertThat(variables.getString("checkoutTraceId")).isEqualTo("trace-456");
        assertThat(request.getJSONObject("extensions").getJSONObject("persistedQuery")
                .getString("sha256Hash"))
                .isEqualTo("fc0d0641bb5b1735c3a81893037b657411b45b9be395cf01b93e4c0c00db8ac6");
    }

    @Test
    void acceptsOnlyADataResponseWithoutGraphqlErrors() {
        JSONObject success = new JSONObject(true)
                .fluentPut("data", new JSONObject(true)
                        .fluentPut("updateSellerListing", new JSONObject(true).fluentPut("id", "listing-123")));
        JSONObject partialFailure = new JSONObject(true)
                .fluentPut("data", new JSONObject(true)
                        .fluentPut("updateSellerListing", new JSONObject(true).fluentPut("id", "listing-123")))
                .fluentPut("errors", com.alibaba.fastjson.JSON.parseArray(
                        "[{\"message\":\"update rejected\"}]"));

        assertThat(StockXClient.isUpdateSellerListingAccepted(success)).isTrue();
        assertThat(StockXClient.isUpdateSellerListingAccepted(partialFailure)).isFalse();
    }
}
