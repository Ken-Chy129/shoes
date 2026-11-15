package cn.ken.shoes.client;

import cn.ken.shoes.service.EbayApplicationTokenService;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayRateLimitApiClientTest {

    private MockWebServer server;
    private EbayRateLimitApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayApplicationTokenService tokenService = mock(EbayApplicationTokenService.class);
        when(tokenService.getValidAccessToken()).thenReturn("app-token");
        client = new EbayRateLimitApiClient(tokenService, new OkHttpClient(),
                server.url("/developer/analytics/v1_beta/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesResourcesSortedByUsage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"rateLimits":[{"apiContext":"TradingAPI","apiName":"TradingAPI","resources":[
                  {"name":"GetOrders","rates":[{"count":1,"limit":5000,"remaining":7,"reset":"2026-11-15T08:00:00.000Z","timeWindow":86400}]},
                  {"name":"ReviseInventoryStatus","rates":[{"count":4490,"limit":5000,"remaining":7,"reset":"2026-11-15T08:00:00.000Z","timeWindow":86400}]},
                  {"name":"AddItem","rates":[{"count":0,"limit":100000,"remaining":100000,"reset":"2026-11-15T08:00:00.000Z","timeWindow":86400}]}
                ]}]}
                """));

        List<EbayRateLimitApiClient.ResourceRate> rates = client.getRateLimits("TradingAPI");

        assertThat(rates).extracting(EbayRateLimitApiClient.ResourceRate::name)
                .containsExactly("ReviseInventoryStatus", "GetOrders", "AddItem");
        assertThat(rates.getFirst().remaining()).isEqualTo(7);
        assertThat(rates.getFirst().reset()).isEqualTo(Instant.parse("2026-11-15T08:00:00Z"));
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/developer/analytics/v1_beta/rate_limit/?api_name=TradingAPI");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer app-token");
    }

    @Test
    void surfacesHttpErrors() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));

        assertThatThrownBy(() -> client.getRateLimits("TradingAPI"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("401");
    }
}
