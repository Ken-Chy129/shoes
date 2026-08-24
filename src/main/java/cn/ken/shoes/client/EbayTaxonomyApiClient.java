package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayApplicationTokenService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class EbayTaxonomyApiClient {

    private final EbayApplicationTokenService tokenService;
    private final OkHttpClient httpClient;
    private final HttpUrl taxonomyBaseUrl;
    private final String locale;

    @Autowired
    public EbayTaxonomyApiClient(EbayProperties properties,
                                 EbayApplicationTokenService tokenService) {
        this(properties, tokenService,
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getTaxonomyApiEndpoint());
    }

    EbayTaxonomyApiClient(EbayProperties properties, EbayApplicationTokenService tokenService,
                          OkHttpClient httpClient, String taxonomyBaseUrl) {
        this.tokenService = tokenService;
        this.httpClient = httpClient;
        this.taxonomyBaseUrl = requireHttpUrl(taxonomyBaseUrl);
        this.locale = properties.getDefaultContentLanguage();
    }

    public JSONObject getCategorySuggestions(String categoryTreeId, String query) {
        HttpUrl url = categoryTreeUrl(categoryTreeId, "get_category_suggestions")
                .newBuilder()
                .addQueryParameter("q", requireValue(query, "query"))
                .build();
        return get(url);
    }

    public JSONObject getItemAspectsForCategory(String categoryTreeId, String categoryId) {
        HttpUrl url = categoryTreeUrl(categoryTreeId, "get_item_aspects_for_category")
                .newBuilder()
                .addQueryParameter("category_id", numericId(categoryId, "categoryId"))
                .build();
        return get(url);
    }

    private JSONObject get(HttpUrl url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Accept-Language", locale)
                .header("Authorization", "Bearer " + tokenService.getValidAccessToken())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = responseBody(response.body());
            if (response.code() != 200) {
                throw new EbayApiException("eBay Taxonomy API request failed (HTTP "
                        + response.code() + ")");
            }
            try {
                JSONObject parsed = JSON.parseObject(body);
                if (parsed == null) {
                    throw new EbayApiException("eBay Taxonomy API returned malformed JSON");
                }
                return parsed;
            } catch (JSONException e) {
                throw new EbayApiException("eBay Taxonomy API returned malformed JSON", e);
            }
        } catch (IOException e) {
            throw new EbayApiException("eBay Taxonomy API request failed due to a network error", e);
        }
    }

    private HttpUrl categoryTreeUrl(String categoryTreeId, String operation) {
        return taxonomyBaseUrl.newBuilder()
                .addPathSegment("category_tree")
                .addPathSegment(numericId(categoryTreeId, "categoryTreeId"))
                .addPathSegment(operation)
                .build();
    }

    private String numericId(String value, String field) {
        String normalized = requireValue(value, field);
        if (!normalized.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        return normalized;
    }

    private String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private HttpUrl requireHttpUrl(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !("https".equals(parsed.scheme()) || isLoopbackHttp(parsed))) {
            throw new IllegalArgumentException("eBay Taxonomy endpoint must use HTTPS");
        }
        return parsed;
    }

    private boolean isLoopbackHttp(HttpUrl url) {
        return "http".equals(url.scheme())
                && ("localhost".equals(url.host()) || "127.0.0.1".equals(url.host()));
    }

    private String responseBody(ResponseBody body) throws IOException {
        return body == null ? "" : body.string();
    }
}
