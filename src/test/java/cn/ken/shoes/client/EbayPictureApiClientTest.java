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
                server.url("/ws/api.dll").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void uploadsAnExternalImageToEbayPictureServices() throws Exception {
        server.enqueue(xmlResponse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <UploadSiteHostedPicturesResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <SiteHostedPictureDetails>
                    <FullURL>https://i.ebayimg.com/images/g/abc/s-l1600.jpg</FullURL>
                  </SiteHostedPictureDetails>
                </UploadSiteHostedPicturesResponse>
                """));

        String hostedUrl = client.uploadExternalPicture(
                "https://cdn.example.com/shoe?a=1&b=2", "DD1391-100-1");

        assertThat(hostedUrl).isEqualTo(
                "https://i.ebayimg.com/images/g/abc/s-l1600.jpg");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader("X-EBAY-API-CALL-NAME"))
                .isEqualTo("UploadSiteHostedPictures");
        assertThat(request.getHeader("X-EBAY-API-IAF-TOKEN"))
                .isEqualTo("seller-access-token");
        assertThat(request.getBody().readUtf8())
                .contains("<ExternalPictureURL>https://cdn.example.com/shoe?a=1&amp;b=2</ExternalPictureURL>")
                .contains("<PictureName>DD1391-100-1</PictureName>");
    }

    @Test
    void rejectsAnEpsResponseWithoutAHostedUrl() {
        server.enqueue(xmlResponse("""
                <UploadSiteHostedPicturesResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Failure</Ack>
                </UploadSiteHostedPicturesResponse>
                """));

        assertThatThrownBy(() -> client.uploadExternalPicture(
                "https://cdn.example.com/private-name.jpg", "STYLE-1"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("图片托管失败")
                .hasMessageNotContaining("private-name");
    }

    @Test
    void rejectsXmlResponsesThatAttemptToDeclareExternalEntities() {
        server.enqueue(xmlResponse("""
                <?xml version="1.0"?>
                <!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <UploadSiteHostedPicturesResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <SiteHostedPictureDetails><FullURL>&xxe;</FullURL></SiteHostedPictureDetails>
                </UploadSiteHostedPicturesResponse>
                """));

        assertThatThrownBy(() -> client.uploadExternalPicture(
                "https://cdn.example.com/shoe.jpg", "STYLE-1"))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("响应格式异常")
                .hasMessageNotContaining("/etc/passwd");
    }

    private MockResponse xmlResponse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/xml; charset=utf-8")
                .setBody(body);
    }
}
