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
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayMarketingApiClientTest {

    private MockWebServer server;
    private EbayMarketingApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("token");
        client = new EbayMarketingApiClient(oauthService, new OkHttpClient(),
                server.url("/sell/marketing/v1").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void collectsCostPerSaleRatesFromRunningCampaigns() throws Exception {
        server.enqueue(json("""
                {"total":2,"campaigns":[
                  {"campaignId":"C1","campaignStatus":"RUNNING",
                   "fundingStrategy":{"fundingModel":"COST_PER_SALE","bidPercentage":"4.0"}},
                  {"campaignId":"C2","campaignStatus":"RUNNING",
                   "fundingStrategy":{"fundingModel":"COST_PER_CLICK"}}]}
                """));
        server.enqueue(json("""
                {"total":3,"ads":[
                  {"listingId":"111","adStatus":"ACTIVE"},
                  {"listingId":"222","adStatus":"ACTIVE","bidPercentage":"6.5"},
                  {"listingId":"333","adStatus":"PAUSED"}]}
                """));

        Map<String, BigDecimal> rates = client.getRunningAdRates();

        assertThat(rates).containsOnlyKeys("111", "222");
        assertThat(rates.get("111")).isEqualByComparingTo("4.0");
        assertThat(rates.get("222")).isEqualByComparingTo("6.5");
        RecordedRequest campaigns = server.takeRequest();
        assertThat(campaigns.getPath()).contains("/ad_campaign?campaign_status=RUNNING");
        assertThat(campaigns.getHeader("Authorization")).isEqualTo("Bearer token");
        assertThat(server.takeRequest().getPath()).contains("/ad_campaign/C1/ad?");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void reportsMissingScopeOn403() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));
        assertThatThrownBy(() -> client.getRunningAdRates()).hasMessageContaining("重新授权");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
