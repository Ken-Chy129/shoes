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

class EbayFinancesApiClientTest {

    private MockWebServer server;
    private EbayFinancesApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("seller-access-token");
        client = new EbayFinancesApiClient(oauthService, new OkHttpClient(),
                server.url("/sell/finances/v1/").toString(), "EBAY_US");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesTransactionsByDateWithMarketplaceHeader() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"total\":2,\"transactions\":[{\"transactionId\":\"A\"},{\"transactionId\":\"B\"}]}"));

        var transactions = client.getTransactions(Instant.parse("2026-08-11T00:00:00Z"),
                Instant.parse("2026-11-09T00:00:00Z"));

        assertThat(transactions).extracting(t -> t.getString("transactionId")).containsExactly("A", "B");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer seller-access-token");
        assertThat(request.getHeader("X-EBAY-C-MARKETPLACE-ID")).isEqualTo("EBAY_US");
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/sell/finances/v1/transaction");
        assertThat(request.getRequestUrl().queryParameter("filter"))
                .isEqualTo("transactionDate:[2026-08-11T00:00:00.000Z..2026-11-09T00:00:00.000Z]");
    }

    @Test
    void reportsMissingScope() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"errors\":[]}"));

        assertThatThrownBy(() -> client.getTransactions(Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(EbayApiException.class).hasMessageContaining("财务");
    }
}
