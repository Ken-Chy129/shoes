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

class EbayTradingApiClientTest {

    private MockWebServer server;
    private EbayTradingApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("seller-access-token");
        client = new EbayTradingApiClient(oauthService, new OkHttpClient(), server.url("/ws/api.dll").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void requestsOnePageOfSellerOrders() throws Exception {
        server.enqueue(xml("""
                <GetOrdersResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>2</TotalNumberOfPages><TotalNumberOfEntries>101</TotalNumberOfEntries></PaginationResult>
                  <HasMoreOrders>true</HasMoreOrders>
                  <OrderArray><Order><OrderID>1</OrderID></Order><Order><OrderID>2</OrderID></Order></OrderArray>
                </GetOrdersResponse>
                """));

        var page = client.getOrders(Instant.parse("2026-07-16T00:00:00.123Z"),
                Instant.parse("2026-10-14T00:00:00Z"), 1);

        assertThat(page.orders()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.totalPages()).isEqualTo(2);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-EBAY-API-CALL-NAME")).isEqualTo("GetOrders");
        assertThat(request.getHeader("X-EBAY-API-IAF-TOKEN")).isEqualTo("seller-access-token");
        assertThat(request.getBody().readUtf8())
                .contains("<CreateTimeFrom>2026-07-16T00:00:00Z</CreateTimeFrom>")
                .contains("<OrderStatus>All</OrderStatus>")
                .contains("<OrderRole>Seller</OrderRole>")
                .contains("<PageNumber>1</PageNumber>");
    }

    @Test
    void collectsLineItemsThatAlreadyHaveFeedbackLeft() {
        server.enqueue(xml("""
                <GetFeedbackResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <FeedbackDetailArray>
                    <FeedbackDetail><OrderLineItemID>1-2</OrderLineItemID></FeedbackDetail>
                    <FeedbackDetail><ItemID>3</ItemID><TransactionID>4</TransactionID></FeedbackDetail>
                  </FeedbackDetailArray>
                </GetFeedbackResponse>
                """));

        assertThat(client.getFeedbackLeftLineItemIds()).containsExactlyInAnyOrder("1-2", "3-4");
    }

    @Test
    void surfacesTradingApiErrors() {
        server.enqueue(xml("""
                <GetOrdersResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Failure</Ack>
                  <Errors><ShortMessage>Invalid date range.</ShortMessage><ErrorCode>21843</ErrorCode>
                    <SeverityCode>Error</SeverityCode></Errors>
                </GetOrdersResponse>
                """));

        assertThatThrownBy(() -> client.getOrders(Instant.now(), Instant.now(), 1))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("21843");
    }

    private static MockResponse xml(String body) {
        return new MockResponse().setHeader("Content-Type", "text/xml").setBody(body);
    }
}
