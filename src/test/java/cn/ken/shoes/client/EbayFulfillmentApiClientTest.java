package cn.ken.shoes.client;

import cn.ken.shoes.service.EbayOAuthService;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayFulfillmentApiClientTest {

    private MockWebServer server;
    private EbayFulfillmentApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("seller-access-token");
        client = new EbayFulfillmentApiClient(oauthService, new OkHttpClient(),
                server.url("/sell/fulfillment/v1/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesOrdersByCreationDateWithBearerToken() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"total\":2,\"orders\":[{\"orderId\":\"A\"},{\"orderId\":\"B\"}]}"));

        var orders = client.getOrders(Instant.parse("2026-07-16T00:00:00Z"), Instant.parse("2026-10-14T00:00:00.5Z"));

        assertThat(orders).extracting(o -> o.getString("orderId")).containsExactly("A", "B");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer seller-access-token");
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/sell/fulfillment/v1/order");
        assertThat(request.getRequestUrl().queryParameter("filter"))
                .isEqualTo("creationdate:[2026-07-16T00:00:00.000Z..2026-10-14T00:00:00.500Z]");
        assertThat(request.getRequestUrl().queryParameter("offset")).isEqualTo("0");
    }

    @Test
    void readsShippingFulfillmentsAndRejectsMissingScope() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"fulfillments\":[{\"shippedDate\":\"2026-10-10T14:54:01.000Z\",\"lineItems\":[{\"lineItemId\":\"1\"}]}]}"));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"errors\":[]}"));

        assertThat(client.getShippingFulfillments("16-15259-24525")).hasSize(1);
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/sell/fulfillment/v1/order/16-15259-24525/shipping_fulfillment");
        assertThatThrownBy(() -> client.getOrders(Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(EbayApiException.class).hasMessageContaining("403");
    }
}
