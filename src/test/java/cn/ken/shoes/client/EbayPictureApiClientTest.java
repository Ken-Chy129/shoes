package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayOAuthService;
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

class EbayPictureApiClientTest {

    private MockWebServer server;
    private EbayPictureApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayProperties properties = new EbayProperties();
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("seller-access-token");
        client = new EbayPictureApiClient(properties, oauthService,
                new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
                server.url("/commerce/media/v1_beta/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void uploadsAnExternalImageThroughTheMediaApi() throws Exception {
        // NTAwWDcwMA== -> 500X700
        server.enqueue(json(201, """
                {"expirationDate":"2026-12-02T17:03:01Z",
                 "imageUrl":"https://i.ebayimg.com/00/s/NTAwWDcwMA==/z/abc/$_1.JPG?set_id=8800005007"}
                """));

        var hostedUrl = client.uploadExternalPicture(
                "https://cdn.example.com/shoe?a=1&b=2", "DD1391-100-1");

        assertThat(hostedUrl).contains(
                "https://i.ebayimg.com/00/s/NTAwWDcwMA==/z/abc/$_1.JPG?set_id=8800005007");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .isEqualTo("/commerce/media/v1_beta/image/create_image_from_url");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer seller-access-token");
        assertThat(request.getBody().readUtf8())
                .isEqualTo("{\"imageUrl\":\"https://cdn.example.com/shoe?a=1&b=2\"}");
    }

    @Test
    void marksAnUploadedPictureAsIneligibleWhenItsLongestSideIsBelow500Pixels() {
        // NDc2WDQ3Ng== -> 476X476
        server.enqueue(json(201, """
                {"imageUrl":"https://i.ebayimg.com/00/s/NDc2WDQ3Ng==/z/small/$_1.JPG"}
                """));

        assertThat(client.uploadExternalPicture(
                "https://cdn.example.com/small.jpg", "SMALL-1"))
                .isEmpty();
    }

    @Test
    void readsTheLongestSideFromTheEpsUrl() {
        assertThat(EbayPictureApiClient.maxDimension(
                "https://i.ebayimg.com/00/s/MTYwMFgxMjAw/z/x/$_1.JPG")).isEqualTo(1600);
        assertThat(EbayPictureApiClient.maxDimension(
                "https://i.ebayimg.com/images/g/abc/s-l1600.jpg")).isZero();
    }

    @Test
    void rejectsAResponseWithoutAHostedUrl() {
        server.enqueue(json(201, "{}"));

        assertThatThrownBy(() -> client.uploadExternalPicture(
                "https://cdn.example.com/private-name.jpg", "STYLE-1"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("图片托管失败")
                .hasMessageNotContaining("private-name");
    }

    @Test
    void reportsRateLimitingAsAQuotaFailure() {
        server.enqueue(json(429, "{\"errors\":[{\"errorId\":2001,\"message\":\"Too many requests\"}]}"));

        assertThatThrownBy(() -> client.uploadExternalPicture(
                "https://cdn.example.com/shoe.jpg", "STYLE-1"))
                .isInstanceOf(EbayQuotaExceededException.class)
                .hasMessageContaining("额度已用完");
    }

    @Test
    void surfacesEbayErrorIdAndMessageWhenHostingFails() {
        server.enqueue(json(400, """
                {"errors":[{"errorId":190204,"domain":"API_MEDIA","category":"REQUEST",
                  "message":"No valid image can be downloaded from the provided imageUrl."}]}
                """));

        assertThatThrownBy(() -> client.uploadExternalPicture(
                "https://cdn.example.com/shoe.jpg", "STYLE-1"))
                .isInstanceOf(EbayApiException.class)
                .isNotInstanceOf(EbayQuotaExceededException.class)
                .hasMessage("eBay图片托管失败(HTTP 400)：190204 "
                        + "No valid image can be downloaded from the provided imageUrl.");
    }

    private MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
