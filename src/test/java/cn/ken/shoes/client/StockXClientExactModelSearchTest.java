package cn.ken.shoes.client;

import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientExactModelSearchTest {

    @Test
    void stopsAfterExactModelAndOnlyLoadsItsMarketPrice() {
        StubStockXClient client = new StubStockXClient();
        StockXAccount account = new StockXAccount();
        account.setName("account-1");
        account.setAuthorization("Bearer test");

        List<StockXPriceExcel> result = client.searchExactItemWithPrice(
                "ALIAS-1 / STYLE-1", "shoes", "US", account);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.getModelNo()).isEqualTo("STYLE-1");
            assertThat(item.getId()).isEqualTo("variant-1");
            assertThat(item.getStandardPrice()).isEqualTo(300);
            assertThat(item.getFlexPrice()).isEqualTo(315);
            assertThat(item.getLast90DaysSales()).isEqualTo(826);
        });
        assertThat(client.calls).containsExactly(
                "search:ALIAS-1", "product:wrong-product", "product:exact-product", "market:exact-product");
    }

    private static class StubStockXClient extends StockXClient {
        private final List<String> calls = new ArrayList<>();

        @Override
        protected JSONObject queryPro(String body, Headers headers, String accountName) {
            JSONObject request = JSON.parseObject(body);
            String operation = request.getString("operationName");
            String id = request.getJSONObject("variables").getString("id");
            if ("getDiscoveryData".equals(operation)) {
                String query = request.getJSONObject("variables").getString("query");
                calls.add("search:" + query);
                return JSON.parseObject("""
                        {"data":{"browse":{"results":{"edges":[
                          {"node":{"urlKey":"wrong-product","title":"Wrong"}},
                          {"node":{"urlKey":"exact-product","title":"Exact"}},
                          {"node":{"urlKey":"ignored-product","title":"Ignored"}}
                        ]}}}}
                        """);
            }
            if ("GetProduct".equals(operation)) {
                calls.add("product:" + id);
                String styleId = "exact-product".equals(id) ? "STYLE-1" : "OTHER-1";
                return JSON.parseObject("""
                        {"data":{"product":{"styleId":"%s","brand":"Brand","variants":[
                          {"id":"variant-1","sizeChart":{"displayOptions":[
                            {"type":"us","size":"9"},{"type":"eu","size":"42.5"}
                          ]}}
                        ]}}}
                        """.formatted(styleId));
            }
            if ("GetMarketData".equals(operation)) {
                calls.add("market:" + id);
                assertThat(request.getJSONObject("variables").getBoolean("includeProcessingFeeForPricing"))
                        .isFalse();
                assertThat(request.getJSONObject("extensions")
                        .getJSONObject("persistedQuery").getString("sha256Hash"))
                        .isEqualTo("5ba554f0c3f881e67a555da21d7f16a36416a67ef9898bc8b9a78c2371641453");
                return JSON.parseObject("""
                        {"data":{"product":{"variants":[
                          {"id":"variant-1","market":{"state":{
                            "lowestAsk":{"amount":300},"highestBid":{"amount":250},
                            "askServiceLevels":{
                              "standard":{"lowest":{"amount":300}},
                              "expressStandard":{"lowest":{"amount":315}}
                            }
                          },"salesInformation":{"salesLast72Hours":68},
                          "statistics":{"last90Days":{"averagePrice":173,"salesCount":826}}}}
                        ]}}}
                        """);
            }
            throw new AssertionError("Unexpected operation: " + operation);
        }
    }
}
