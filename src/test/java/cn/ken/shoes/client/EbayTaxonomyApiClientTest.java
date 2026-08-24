package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayApplicationTokenService;
import com.alibaba.fastjson.JSONObject;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayTaxonomyApiClientTest {

    private MockWebServer server;
    private EbayTaxonomyApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayProperties properties = new EbayProperties();
        EbayApplicationTokenService tokenService = mock(EbayApplicationTokenService.class);
        when(tokenService.getValidAccessToken()).thenReturn("access-token");
        client = new EbayTaxonomyApiClient(
                properties,
                tokenService,
                new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
                server.url("/commerce/taxonomy/v1/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void requestsCategorySuggestionsWithEncodedQueryAndLocale() throws Exception {
        server.enqueue(jsonResponse("{\"categorySuggestions\":[]}"));

        JSONObject response = client.getCategorySuggestions("0", "Nike Dunk men's shoes");

        assertThat(response.getJSONArray("categorySuggestions")).isEmpty();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo(
                "/commerce/taxonomy/v1/category_tree/0/get_category_suggestions?q=Nike%20Dunk%20men%27s%20shoes");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer access-token");
        assertThat(request.getHeader("Accept-Language")).isEqualTo("en-US");
    }

    @Test
    void requestsItemAspectsForTheResolvedCategory() throws Exception {
        server.enqueue(jsonResponse("{\"aspects\":[{\"localizedAspectName\":\"Brand\"}]}"));

        JSONObject response = client.getItemAspectsForCategory("0", "15709");

        assertThat(response.getJSONArray("aspects").getJSONObject(0)
                .getString("localizedAspectName")).isEqualTo("Brand");
        assertThat(server.takeRequest().getPath()).isEqualTo(
                "/commerce/taxonomy/v1/category_tree/0/get_item_aspects_for_category?category_id=15709");
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
