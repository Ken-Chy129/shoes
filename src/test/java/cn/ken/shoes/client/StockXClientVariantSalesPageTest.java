package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class StockXClientVariantSalesPageTest {
    @Test
    void requestsBuyerSalesForExactVariantCurrencyAndCursor() {
        AtomicReference<JSONObject> request = new AtomicReference<>();
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryDirectPro(String body, String country, StockXAccount account) {
                request.set(JSONObject.parseObject(body));
                assertThat(country).isEqualTo("HK");
                return response("variant-1", "HKD");
            }
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount account) {
                throw new AssertionError("should use direct read");
            }
        };
        StockXAccount account = new StockXAccount();
        account.setCountry("HK");
        account.setApiKey("test-key");

        JSONObject page = client.queryVariantSalesPage("variant-1", "HKD", "cursor", account);

        assertThat(page.getJSONArray("edges")).isEmpty();
        assertThat(request.get().getJSONObject("variables"))
                .containsEntry("id", "variant-1").containsEntry("currencyCode", "HKD")
                .containsEntry("viewerContext", "BUYER").containsEntry("after", "cursor");
    }

    @Test
    void rejectsWrongVariantWrongCurrencyAndGraphQlErrors() {
        for (JSONObject response : new JSONObject[]{
                response("variant-2", "USD"), response("variant-1", "HKD"),
                JSONObject.parseObject("{\"errors\":[{\"message\":\"unavailable\"}]}"),
                JSONObject.parseObject("{\"data\":{\"variant\":{}}}")}) {
            assertThatThrownBy(() -> client(response).queryVariantSalesPage("variant-1", "USD", null, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void preservesNullResponseForTaskRetryInsteadOfReturningAnEmptyHistory() {
        assertThat(client(null).queryVariantSalesPage("variant-1", "USD", null, null)).isNull();
    }

    private StockXClient client(JSONObject response) {
        return new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount account) {
                return response;
            }
        };
    }

    private JSONObject response(String variant, String currency) {
        return JSONObject.parseObject("""
                {"data":{"variant":{"id":"%s","market":{"currencyCode":"%s",
                "sales":{"edges":[],"pageInfo":{"total":0,"endCursor":null}}}}}}
                """.formatted(variant, currency));
    }
}
