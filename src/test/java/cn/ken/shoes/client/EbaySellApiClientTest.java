package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbaySellApiClientTest {

    private MockWebServer server;
    private EbaySellApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayProperties properties = new EbayProperties();
        properties.setEnvironment("sandbox");
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("access-token");
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
        client = new EbaySellApiClient(
                properties,
                oauthService,
                httpClient,
                server.url("/sell/inventory/v1/").toString(),
                server.url("/sell/account/v1/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void replacesInventoryItemWithEncodedSkuAndRequiredHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        JSONObject payload = JSON.parseObject("""
                {"condition":"NEW","availability":{"shipToLocationAvailability":{"quantity":2}}}
                """);

        client.createOrReplaceInventoryItem("shoe/42 blue", payload, "en-US");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/sell/inventory/v1/inventory_item/shoe%2F42%20blue");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer access-token");
        assertThat(request.getHeader("Content-Language")).isEqualTo("en-US");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertThat(JSON.parseObject(request.getBody().readUtf8()))
                .isEqualTo(payload);
    }

    @Test
    void createsAndPublishesOffer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"offerId\":\"offer-123\"}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"listingId\":\"listing-456\"}"));
        JSONObject offer = JSON.parseObject("""
                {"sku":"sku-1","marketplaceId":"EBAY_US","format":"FIXED_PRICE"}
                """);

        String offerId = client.createOffer(offer, "en-US");
        String listingId = client.publishOffer(offerId);

        assertThat(offerId).isEqualTo("offer-123");
        assertThat(listingId).isEqualTo("listing-456");
        RecordedRequest createRequest = server.takeRequest();
        assertThat(createRequest.getPath()).isEqualTo("/sell/inventory/v1/offer");
        assertThat(createRequest.getHeader("Content-Language")).isEqualTo("en-US");
        RecordedRequest publishRequest = server.takeRequest();
        assertThat(publishRequest.getPath()).isEqualTo("/sell/inventory/v1/offer/offer-123/publish");
        assertThat(publishRequest.getHeader("Content-Type")).startsWith("application/json");
        assertThat(publishRequest.getBodySize()).isZero();
    }

    @Test
    void createsAndPublishesAnInventoryItemGroup() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(jsonResponse("{\"listingId\":\"listing-group-456\"}"));
        JSONObject group = JSON.parseObject("""
                {"title":"Test Sneaker","variantSKUs":["sku-9","sku-10"]}
                """);

        client.createOrReplaceInventoryItemGroup("style/1", group, "en-US");
        String listingId = client.publishOfferByInventoryItemGroup("style/1", "EBAY_US");

        assertThat(listingId).isEqualTo("listing-group-456");
        RecordedRequest groupRequest = server.takeRequest();
        assertThat(groupRequest.getMethod()).isEqualTo("PUT");
        assertThat(groupRequest.getPath())
                .isEqualTo("/sell/inventory/v1/inventory_item_group/style%2F1");
        assertThat(groupRequest.getHeader("Content-Language")).isEqualTo("en-US");
        assertThat(JSON.parseObject(groupRequest.getBody().readUtf8())).isEqualTo(group);
        RecordedRequest publishRequest = server.takeRequest();
        assertThat(publishRequest.getMethod()).isEqualTo("POST");
        assertThat(publishRequest.getPath())
                .isEqualTo("/sell/inventory/v1/offer/publish_by_inventory_item_group");
        assertThat(JSON.parseObject(publishRequest.getBody().readUtf8()))
                .containsEntry("inventoryItemGroupKey", "style/1")
                .containsEntry("marketplaceId", "EBAY_US");
    }

    @Test
    void createsLocationAndReadsListingPrerequisites() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(jsonResponse("{\"locations\":[{\"merchantLocationKey\":\"sz-main\"}]}"));
        server.enqueue(jsonResponse("{\"fulfillmentPolicies\":[{\"fulfillmentPolicyId\":\"f-1\"}]}"));
        server.enqueue(jsonResponse("{\"paymentPolicies\":[{\"paymentPolicyId\":\"p-1\"}]}"));
        server.enqueue(jsonResponse("{\"returnPolicies\":[{\"returnPolicyId\":\"r-1\"}]}"));

        client.createInventoryLocation("sz/main", new JSONObject().fluentPut("name", "Shenzhen"));
        JSONObject locations = client.getInventoryLocations();
        JSONObject fulfillment = client.getFulfillmentPolicies("EBAY_US");
        JSONObject payment = client.getPaymentPolicies("EBAY_US");
        JSONObject returns = client.getReturnPolicies("EBAY_US");

        assertThat(locations.getJSONArray("locations")).hasSize(1);
        assertThat(fulfillment.getJSONArray("fulfillmentPolicies")).hasSize(1);
        assertThat(payment.getJSONArray("paymentPolicies")).hasSize(1);
        assertThat(returns.getJSONArray("returnPolicies")).hasSize(1);
        assertThat(server.takeRequest().getPath()).isEqualTo("/sell/inventory/v1/location/sz%2Fmain");
        assertThat(server.takeRequest().getPath()).isEqualTo("/sell/inventory/v1/location");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/account/v1/fulfillment_policy?marketplace_id=EBAY_US");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/account/v1/payment_policy?marketplace_id=EBAY_US");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/account/v1/return_policy?marketplace_id=EBAY_US");
    }

    @Test
    void readsActiveOffersWithPaginationAndUpdatesAnOffer() throws Exception {
        server.enqueue(jsonResponse("{\"offers\":[{\"offerId\":\"offer-1\",\"sku\":\"SKU-1\"}],\"total\":2}"));
        server.enqueue(jsonResponse("{\"offers\":[{\"offerId\":\"offer-2\",\"sku\":\"SKU-2\"}],\"total\":2}"));
        server.enqueue(new MockResponse().setResponseCode(204));

        assertThat(client.getActiveOffers("EBAY_US")).extracting(o -> o.getString("offerId"))
                .containsExactly("offer-1", "offer-2");
        JSONObject payload = JSON.parseObject("""
                {"sku":"SKU-1","availableQuantity":0,"pricingSummary":{"price":{"currency":"USD","value":"10.00"}}}
                """);
        client.updateOffer("offer-1", payload, "en-US");

        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/inventory/v1/offer?marketplace_id=EBAY_US&listing_status=ACTIVE&limit=200&offset=0");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/inventory/v1/offer?marketplace_id=EBAY_US&listing_status=ACTIVE&limit=200&offset=1");
        RecordedRequest update = server.takeRequest();
        assertThat(update.getMethod()).isEqualTo("PUT");
        assertThat(update.getPath()).isEqualTo("/sell/inventory/v1/offer/offer-1");
        assertThat(JSON.parseObject(update.getBody().readUtf8())).isEqualTo(payload);
    }

    @Test
    void readsAllOffersForOneSkuWithPagination() throws Exception {
        server.enqueue(jsonResponse("{\"offers\":[{\"offerId\":\"offer-1\",\"sku\":\"SKU/1\"}],\"total\":2}"));
        server.enqueue(jsonResponse("{\"offers\":[{\"offerId\":\"offer-2\",\"sku\":\"SKU/1\"}],\"total\":2}"));

        assertThat(client.getOffersBySku("SKU/1"))
                .extracting(offer -> offer.getString("offerId"))
                .containsExactly("offer-1", "offer-2");

        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/inventory/v1/offer?sku=SKU%2F1&limit=200&offset=0");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/inventory/v1/offer?sku=SKU%2F1&limit=200&offset=1");
    }

    @Test
    void readsInventoryItemGroupAndTreatsNotFoundAsMissing() throws Exception {
        server.enqueue(jsonResponse("""
                {"title":"Test Sneaker","variantSKUs":["sku-9","sku-10"]}
                """));
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThat(client.getInventoryItemGroup("style/1"))
                .get()
                .extracting(group -> group.getString("title"))
                .isEqualTo("Test Sneaker");
        assertThat(client.getInventoryItemGroup("missing/style")).isEmpty();

        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/inventory/v1/inventory_item_group/style%2F1");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/inventory/v1/inventory_item_group/missing%2Fstyle");
    }

    @Test
    void rejectsUnsuccessfulOrMalformedProviderResponsesWithoutLeakingSecrets() {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"errorId\":25002,\"message\":\"bad request access-token\"}]}"));

        assertThatThrownBy(() -> client.createOffer(new JSONObject(), "en-US"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("access-token")
                .hasMessageNotContaining("bad request");

        server.enqueue(jsonResponse("{}"));
        assertThatThrownBy(() -> client.publishOffer("offer-without-listing"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("missing listingId");
    }

    @Test
    void includesSafeEbayErrorDetailsInFailures() {
        server.enqueue(new MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"errors":[{"errorId":25710,"message":"Inventory item not found",
                        "longMessage":"No inventory item exists for the supplied SKU."}]}
                        """));

        assertThatThrownBy(() -> client.createOffer(new JSONObject(), "en-US"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("25710")
                .hasMessageContaining("No inventory item exists for the supplied SKU");
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
