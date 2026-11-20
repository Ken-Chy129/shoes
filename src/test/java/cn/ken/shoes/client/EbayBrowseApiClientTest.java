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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayBrowseApiClientTest {

    private MockWebServer server;
    private EbayBrowseApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayApplicationTokenService tokenService = mock(EbayApplicationTokenService.class);
        when(tokenService.getValidAccessToken()).thenReturn("app-token");
        client = new EbayBrowseApiClient(tokenService, new OkHttpClient(), server.url("/buy/browse/v1/").toString(),
                "EBAY_US");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void searchesSellerWithUsDeliveryAndParsesAuthenticity() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"total":2,"itemSummaries":[
                  {"legacyItemId":"1","qualifiedPrograms":["AUTHENTICITY_GUARANTEE"]},
                  {"legacyItemId":"2"}]}
                """));

        EbayBrowseApiClient.SearchPage page = client.searchSeller("shop", 200);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.byItemId()).isEqualTo(Map.of("1", true, "2", false));
        RecordedRequest request = server.takeRequest();
        assertThat(request.getRequestUrl().queryParameter("filter"))
                .isEqualTo("sellers:{shop},deliveryCountry:US,deliveryPostalCode:10001");
        assertThat(request.getRequestUrl().queryParameter("offset")).isEqualTo("200");
        assertThat(request.getHeader("X-EBAY-C-ENDUSERCTX")).contains("country%3DUS");
    }

    @Test
    void groupListingIsGuaranteedWhenAnySizeIsAndQuotaErrorIsTyped() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"items":[{"seller":{"username":"shop"}},
                          {"seller":{"username":"shop"},"qualifiedPrograms":["AUTHENTICITY_GUARANTEE"]}]}
                """));
        server.enqueue(new MockResponse().setResponseCode(429));

        assertThat(client.getListing("1", true))
                .isEqualTo(new EbayBrowseApiClient.ListingAuthenticity("shop", true));
        assertThatThrownBy(() -> client.getListing("2", false)).isInstanceOf(EbayQuotaExceededException.class);
    }
}
