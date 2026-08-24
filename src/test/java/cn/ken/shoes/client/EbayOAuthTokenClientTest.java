package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EbayOAuthTokenClientTest {

    private MockWebServer server;
    private EbayOAuthTokenClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayProperties properties = new EbayProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        client = new EbayOAuthTokenClient(properties,
                new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
                server.url("/identity/v1/oauth2/token").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void requestsAnApplicationTokenForTaxonomyMetadata() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"app-token\",\"expires_in\":7200}"));

        JSONObject token = client.requestApplicationToken(
                "https://api.ebay.com/oauth/api_scope");

        assertThat(token.getString("access_token")).isEqualTo("app-token");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getBody().readUtf8()).isEqualTo(
                "grant_type=client_credentials&scope=https%3A%2F%2Fapi.ebay.com%2Foauth%2Fapi_scope");
        String expectedBasic = Base64.getEncoder().encodeToString(
                "client-id:client-secret".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(request.getHeader("Authorization")).isEqualTo("Basic " + expectedBasic);
    }
}
