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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXClientExactModelSearchTest {

    @Test
    void exactSearchUsesOfficialCatalogWhenAccountHasApiKey() {
        List<String> urls = new ArrayList<>();
        StockXClient client = new StockXClient() {
            @Override
            protected Object queryCatalog(String url, StockXAccount account) {
                urls.add(url);
                if (url.contains("/catalog/search")) {
                    return JSON.parseObject("""
                            {"products":[
                              {"productId":"wrong-id","urlKey":"wrong","styleId":"DD1391-101","title":"Wrong"},
                              {"productId":"product-id","urlKey":"nike-dunk","styleId":"DD1391-100","title":"Dunk","brand":"Nike"}
                            ]}
                            """);
                }
                if (url.endsWith("/variants")) {
                    return JSON.parseArray("""
                            [{"variantId":"variant-id","sizeChart":{"availableConversions":[
                              {"type":"us m","size":"US M 10"},
                              {"type":"us w","size":"US W 11.5"},
                              {"type":"eu","size":"EU 44"}
                            ]}}]
                            """);
                }
                if (url.endsWith("/market-data")) {
                    return JSON.parseArray("""
                            [{"variantId":"variant-id","highestBidAmount":"48","lowestAskAmount":"64",
                              "flexLowestAskAmount":"66"}]
                            """);
                }
                throw new AssertionError("Unexpected Catalog URL: " + url);
            }

            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                throw new AssertionError("Catalog命中后不应调用GraphQL搜索");
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-1");
        account.setApiKey("api-key");
        account.setAuthorization("Bearer test");

        List<StockXPriceExcel> result = client.searchExactItemWithPrice(
                "DD1391-100", "shoes", "US", account);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo("variant-id");
            assertThat(item.getModelNo()).isEqualTo("DD1391-100");
            assertThat(item.getUsmSize()).isEqualTo("10");
            assertThat(item.getUswSize()).isEqualTo("11.5");
            assertThat(item.getEuSize()).isEqualTo("44");
            assertThat(item.getPurchasePrice()).isEqualTo(48);
            assertThat(item.getStandardPrice()).isEqualTo(64);
            assertThat(item.getFlexPrice()).isEqualTo(66);
        });
        assertThat(urls).hasSize(3);
        assertThat(urls.getFirst()).contains("query=DD1391-100", "pageSize=20");
    }

    @Test
    void exactSearchUsesFullDiscoveryQueryInsteadOfExpiringPersistedHash() {
        AtomicReference<JSONObject> searchRequest = new AtomicReference<>();
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                JSONObject request = JSON.parseObject(body);
                searchRequest.set(request);
                return JSON.parseObject("{\"data\":{\"browse\":{\"results\":{\"edges\":[]}}}}");
            }
        };

        assertThat(client.searchExactItemWithPrice("DD1391-100", "shoes", "US", new StockXAccount()))
                .isEmpty();
        assertThat(searchRequest.get().getString("query")).contains("query getDiscoveryData");
        assertThat(searchRequest.get()).doesNotContainKey("extensions");
        assertThat(searchRequest.get().getJSONObject("variables"))
                .containsEntry("query", "DD1391-100")
                .containsEntry("enableOpenSearch", false);
    }

    @Test
    void exactSearchRejectsGraphQlErrorsInsteadOfReportingMissingProduct() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                return JSON.parseObject("{\"errors\":[{\"message\":\"discovery unavailable\"}]}");
            }
        };

        assertThatThrownBy(() -> client.searchExactItemWithPrice(
                "DD1391-100", "shoes", "US", new StockXAccount()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discovery unavailable");
    }

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
    void readsColorwayProductLineAndMainImageFromTheProductDetail() {
        // 详情接口的 media 是对象（主图 + 36 帧环拍），配色/产品线在 traits 里；
        // 描述为中文时要换成英文描述，否则会原样上到 eBay 美国站。
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                String operation = JSON.parseObject(body).getString("operationName");
                if ("getDiscoveryData".equals(operation)) {
                    return JSON.parseObject("""
                            {"data":{"browse":{"results":{"edges":[
                              {"node":{"urlKey":"detail-product"}}
                            ]}}}}
                            """);
                }
                return JSON.parseObject("""
                        {"data":{"product":{"styleId":"FV4921-600","title":"Nike Kobe 6 Protro Reverse Grinch",
                          "brand":"Nike","description":"耐克 Kobe 6 复刻","model":"Nike Kobe 6 Protro","gender":"men",
                          "productCategory":"sneakers",
                          "media":{"imageUrl":"https://images.stockx.com/images/Kobe-Product.jpg?w=700",
                                   "smallImageUrl":"https://images.stockx.com/images/Kobe-Product.jpg?w=300",
                                   "all360Images":["https://images.stockx.com/360/Kobe/Lv2/img01.jpg?w=559",
                                                   "https://images.stockx.com/360/Kobe/Lv2/img02.jpg?w=559"]},
                          "traits":[{"name":"Style","value":"FV4921-600"},
                                    {"name":"Colorway","value":"Bright Crimson/Black/Electric Green"},
                                    {"name":"Release Date","value":"2023-12-15"},
                                    {"name":"Product Line","value":"Nike Kobe"}]}}}
                        """);
            }
        };

        EbayProductMetadata result = client.queryProductMetadataByModelNo("FV4921-600");

        assertThat(result.getColorway()).isEqualTo("Bright Crimson/Black/Electric Green");
        assertThat(result.getProductLine()).isEqualTo("Nike Kobe");
        assertThat(result.getGender()).isEqualTo("men");
        assertThat(result.getProductType()).isEqualTo("sneakers");
        assertThat(result.getDescription())
                .startsWith("Nike Kobe 6 Protro Reverse Grinch.")
                .contains("Colorway: Bright Crimson/Black/Electric Green")
                .contains("Style code: FV4921-600")
                .contains("Released 2023-12-15")
                .doesNotContain("耐克");
        assertThat(result.getImageUrls()).startsWith(
                "https://images.stockx.com/images/Kobe-Product.jpg?w=700",
                "https://images.stockx.com/360/Kobe/Lv2/img01.jpg?w=559",
                "https://images.stockx.com/360/Kobe/Lv2/img07.jpg?w=559");
        assertThat(result.getImageUrls()).hasSize(7);
    }

    @Test
    void keepsAnEnglishStockXDescriptionAsIs() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Title");
        assertThat(StockXClient.usableEnglishDescription(
                "  The Nike Dunk Low returns.  ", metadata, java.util.Map.of()))
                .isEqualTo("The Nike Dunk Low returns.");
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
            assertThat(item.getAveragePrice90Days()).isEqualByComparingTo("173");
            assertThat(item.getSalesCount90Days()).isEqualTo(826);
            assertThat(item.getAveragePrice30Days()).isNull();
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
                // Viper 网关不认 Iron 的 persisted 哈希，只读行情必须带完整查询文本。
                assertThat(request).doesNotContainKey("extensions");
                assertThat(request.getString("query")).contains("query GetMarketData");
                // Viper 网关只暴露 last90Days{salesCount averagePrice}，搜索导出的 90 天销量依赖这一字段。
                assertThat(request.getString("query")).contains("last90Days { salesCount averagePrice }");
                assertThat(request.getJSONObject("variables"))
                        .containsEntry("currencyCode", "USD")
                        .containsEntry("market", "US");
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
