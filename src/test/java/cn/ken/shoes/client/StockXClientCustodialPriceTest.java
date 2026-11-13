package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class StockXClientCustodialPriceTest {
    @Test
    void queriesOnlyCustodialInventoryWithTheSelectedAccountAndRejectsPartialResponses() {
        AtomicReference<String> response = new AtomicReference<>("""
                {"data":{"viewer":{"sellerListings":{
                  "edges":[],"pageInfo":{"hasNextPage":false}
                }}}}
                """);
        StockXClient client = new StockXClient() {
            @Override protected JSONObject queryPro(String body, Headers headers, String accountName) {
                assertThat(accountName).isEqualTo("fixture");
                JSONObject variables = JSONObject.parseObject(body).getJSONObject("variables");
                assertThat(variables.getJSONObject("filters").getJSONObject("inventoryType")
                        .getJSONArray("in")).containsExactly("CUSTODIAL");
                assertThat(variables.getInteger("pageNumber")).isEqualTo(2);
                return JSONObject.parseObject(response.get());
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("fixture");
        account.setAuthorization("test-only");
        assertThat(client.queryCustodialPricePage(2, account).getJSONArray("edges")).isEmpty();
        for (String invalid : List.of(
                "{\"errors\":[{\"message\":\"private\"}],\"data\":{\"viewer\":{\"sellerListings\":{\"edges\":[],\"pageInfo\":{\"hasNextPage\":false}}}}}",
                "{\"data\":{\"viewer\":{\"sellerListings\":{\"edges\":[]}}}}",
                "{\"data\":{\"viewer\":{\"sellerListings\":{\"edges\":[],\"pageInfo\":{\"hasNextPage\":true}}}}}",
                "{\"message\":\"Unauthorized\"}")) {
            response.set(invalid);
            assertThatThrownBy(() -> client.queryCustodialPricePage(2, account))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private");
        }
    }
}
