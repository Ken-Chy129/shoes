package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayApplicationTokenService;
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

class EbayNotificationPublicKeyClientTest {

    private MockWebServer server;
    private EbayNotificationPublicKeyClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayApplicationTokenService tokenService = mock(EbayApplicationTokenService.class);
        when(tokenService.getValidAccessToken()).thenReturn("application-token");
        client = new EbayNotificationPublicKeyClient(
                new EbayProperties(), tokenService,
                new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
                server.url("/commerce/notification/v1/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesPublicKeyWithApplicationToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"key\":\"PUBLIC-KEY\",\"algorithm\":\"ECDSA\",\"digest\":\"SHA256\"}"));

        EbayNotificationPublicKeyClient.PublicKeyData key = client.getPublicKey("key-123");

        assertThat(key.key()).isEqualTo("PUBLIC-KEY");
        assertThat(key.algorithm()).isEqualTo("ECDSA");
        assertThat(key.digest()).isEqualTo("SHA256");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath())
                .isEqualTo("/commerce/notification/v1/public_key/key-123");
        assertThat(request.getHeader("Authorization"))
                .isEqualTo("Bearer application-token");
    }

    @Test
    void rejectsUnsafeKeyIdsBeforeCallingEbay() {
        assertThatThrownBy(() -> client.getPublicKey("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }
}
