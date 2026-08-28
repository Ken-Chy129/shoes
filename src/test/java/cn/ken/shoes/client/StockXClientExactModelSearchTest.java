package cn.ken.shoes.client;

import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXClientExactModelSearchTest {

    @Test
    void loadsExactProductMetadataAndOfficialImagesWithoutMarketRequest() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                JSONObject request = JSON.parseObject(body);
                String operation = request.getString("operationName");
                if ("getDiscoveryData".equals(operation)) {
                    return JSON.parseObject("""
                            {"data":{"browse":{"results":{"edges":[
                              {"node":{"urlKey":"exact-product","product":{"urlKey":"exact-product","title":"Exact title"}}}
                            ]}}}}
                            """);
                }
                if ("GetProduct".equals(operation)) {
                    return JSON.parseObject("""
                            {"data":{"product":{"styleId":"STYLE-1","title":"Exact title","brand":"Nike",
                              "description":"Description","model":"Dunk Low","media":[
                                {"thumbUrl":"https://images.stockx.com/thumb.jpg","smallImageUrl":"https://images.stockx.com/small.jpg"},
                                {"thumbUrl":"https://images.stockx.com/thumb-2.jpg","smallImageUrl":"https://images.stockx.com/small-2.jpg"}
                              ]}}}
                            """);
                }
                throw new AssertionError("Unexpected market request: " + operation);
            }
        };

        EbayProductMetadata result = client.queryProductMetadataByModelNo("STYLE-1");

        assertThat(result.getTitle()).isEqualTo("Exact title");
        assertThat(result.getBrand()).isEqualTo("Nike");
        assertThat(result.getImageUrls()).containsExactly(
                "https://images.stockx.com/small.jpg", "https://images.stockx.com/small-2.jpg");
    }

    @Test
    void expandsStockXRotationImageIntoRepresentativeFrames() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                String operation = JSON.parseObject(body).getString("operationName");
                if ("getDiscoveryData".equals(operation)) {
                    return JSON.parseObject("""
                            {"data":{"browse":{"results":{"edges":[
                              {"node":{"urlKey":"rotation-product"}}
                            ]}}}}
                            """);
                }
                return JSON.parseObject("""
                        {"data":{"product":{"styleId":"STYLE-2","title":"Rotation title","brand":"Nike",
                          "media":{"smallImageUrl":"https://images.stockx.com/360/rotation/Lv2/img01.jpg?w=576"}}}}
                        """);
            }
        };

        EbayProductMetadata result = client.queryProductMetadataByModelNo("STYLE-2");

        assertThat(result.getImageUrls()).containsExactly(
                "https://images.stockx.com/360/rotation/Lv2/img01.jpg?w=576",
                "https://images.stockx.com/360/rotation/Lv2/img07.jpg?w=576",
                "https://images.stockx.com/360/rotation/Lv2/img13.jpg?w=576",
                "https://images.stockx.com/360/rotation/Lv2/img19.jpg?w=576",
                "https://images.stockx.com/360/rotation/Lv2/img25.jpg?w=576",
                "https://images.stockx.com/360/rotation/Lv2/img31.jpg?w=576");
    }

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
            assertThat(item.getUsmSize()).isEqualTo("8");
            assertThat(item.getUswSize()).isEqualTo("9.5");
            assertThat(item.getEuSize()).isEqualTo("41.5");
            assertThat(item.getStandardPrice()).isEqualTo(300);
            assertThat(item.getFlexPrice()).isEqualTo(315);
            assertThat(item.getLast90DaysSales()).isEqualTo(826);
        });
        assertThat(client.calls).containsExactly(
                "search:ALIAS-1", "product:wrong-product", "product:exact-product", "market:exact-product");
    }

    @Test
    void matchesCaseInsensitiveAliasInsideStockXCombinedModelNumber() {
        StubStockXClient client = new StubStockXClient("DL408-0490/1183C102-751");
        StockXAccount account = new StockXAccount();
        account.setName("account-1");
        account.setAuthorization("Bearer test");

        List<StockXPriceExcel> result = client.searchExactItemWithPrice(
                "1183c102-751", "shoes", "US", account);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.getModelNo()).isEqualTo("DL408-0490/1183C102-751");
            assertThat(item.getId()).isEqualTo("variant-1");
        });
        assertThat(client.calls).containsExactly(
                "search:1183c102-751", "product:wrong-product", "product:exact-product", "market:exact-product");
    }

    @Test
    void propagatesPoolExhaustionFromParallelDetailReads() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                String operation = JSON.parseObject(body).getString("operationName");
                if ("getDiscoveryData".equals(operation)) {
                    return JSON.parseObject("""
                            {"data":{"browse":{"results":{"pageInfo":{"pageCount":1},"edges":[
                              {"node":{"urlKey":"limited-product","title":"Limited"}}
                            ]}}}}
                            """);
                }
                throw new StockXRateLimitException("us-a", 300_000L);
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("us-a");
        account.setCountry("US");
        account.setAuthorization("Bearer test");

        assertThatThrownBy(() -> client.searchItemWithPrice(
                "shoe", 1, "featured", "shoes", "US", account))
                .isInstanceOf(StockXRateLimitException.class);
    }

    private static class StubStockXClient extends StockXClient {
        private final List<String> calls = new ArrayList<>();
        private final String exactStyleId;

        private StubStockXClient() {
            this("STYLE-1");
        }

        private StubStockXClient(String exactStyleId) {
            this.exactStyleId = exactStyleId;
        }

        @Override
        protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
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
                String styleId = "exact-product".equals(id) ? exactStyleId : "OTHER-1";
                return JSON.parseObject("""
                        {"data":{"product":{"styleId":"%s","brand":"Brand","variants":[
                          {"id":"variant-1","sizeChart":{"displayOptions":[
                            {"type":"us m","size":"US M 8"},
                            {"type":"us w","size":"US W 9.5"},
                            {"type":"eu","size":"EU 41.5"}
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

        @Override
        protected JSONObject queryPro(String body, Headers headers, String accountName) {
            throw new AssertionError("账号无关搜索必须经过同区域只读账号池");
        }
    }
}
