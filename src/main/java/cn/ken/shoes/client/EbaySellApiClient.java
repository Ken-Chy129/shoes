package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class EbaySellApiClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final RequestBody EMPTY_JSON_BODY = RequestBody.create(JSON_MEDIA_TYPE, new byte[0]);

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl inventoryBaseUrl;
    private final HttpUrl accountBaseUrl;

    @Autowired
    public EbaySellApiClient(EbayProperties properties, EbayOAuthService oauthService) {
        this(
                properties,
                oauthService,
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getInventoryApiEndpoint(),
                properties.getAccountApiEndpoint());
    }

    EbaySellApiClient(EbayProperties properties, EbayOAuthService oauthService,
                      OkHttpClient httpClient, String inventoryBaseUrl, String accountBaseUrl) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        this.inventoryBaseUrl = requireHttpUrl(inventoryBaseUrl);
        this.accountBaseUrl = requireHttpUrl(accountBaseUrl);
    }

    public void createOrReplaceInventoryItem(String sku, JSONObject payload, String contentLanguage) {
        HttpUrl url = inventoryUrl("inventory_item").newBuilder()
                .addPathSegment(requireValue(sku, "sku"))
                .build();
        Request request = request(url, contentLanguage)
                .put(jsonBody(payload))
                .build();
        execute(request, Set.of(200, 204));
    }

    public String createOffer(JSONObject payload, String contentLanguage) {
        Request request = request(inventoryUrl("offer"), contentLanguage)
                .post(jsonBody(payload))
                .build();
        JSONObject response = execute(request, Set.of(200, 201));
        return requiredResponseField(response, "offerId");
    }

    public String publishOffer(String offerId) {
        HttpUrl url = inventoryUrl("offer").newBuilder()
                .addPathSegment(requireValue(offerId, "offerId"))
                .addPathSegment("publish")
                .build();
        Request request = request(url, null)
                .post(EMPTY_JSON_BODY)
                .build();
        JSONObject response = execute(request, Set.of(200));
        return requiredResponseField(response, "listingId");
    }

    public void createInventoryLocation(String merchantLocationKey, JSONObject payload) {
        HttpUrl url = inventoryUrl("location").newBuilder()
                .addPathSegment(requireValue(merchantLocationKey, "merchantLocationKey"))
                .build();
        Request request = request(url, null)
                .post(jsonBody(payload))
                .build();
        execute(request, Set.of(204));
    }

    public JSONObject getInventoryLocations() {
        return get(inventoryUrl("location"));
    }

    public JSONObject getFulfillmentPolicies(String marketplaceId) {
        return get(policyUrl("fulfillment_policy", marketplaceId));
    }

    public JSONObject getPaymentPolicies(String marketplaceId) {
        return get(policyUrl("payment_policy", marketplaceId));
    }

    public JSONObject getReturnPolicies(String marketplaceId) {
        return get(policyUrl("return_policy", marketplaceId));
    }

    private JSONObject get(HttpUrl url) {
        Request request = request(url, null).get().build();
        return execute(request, Set.of(200));
    }

    private HttpUrl policyUrl(String resource, String marketplaceId) {
        return accountUrl(resource).newBuilder()
                .addQueryParameter("marketplace_id", requireValue(marketplaceId, "marketplaceId"))
                .build();
    }

    private Request.Builder request(HttpUrl url, String contentLanguage) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + oauthService.getValidAccessToken());
        if (contentLanguage != null && !contentLanguage.isBlank()) {
            builder.header("Content-Language", contentLanguage);
        }
        return builder;
    }

    private RequestBody jsonBody(JSONObject payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        return RequestBody.create(JSON_MEDIA_TYPE, payload.toJSONString());
    }

    private JSONObject execute(Request request, Set<Integer> expectedStatusCodes) {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseText = responseText(response.body());
            if (!expectedStatusCodes.contains(response.code())) {
                throw new EbayApiException("eBay API request failed (HTTP " + response.code() + ")");
            }
            if (responseText.isBlank()) {
                return new JSONObject();
            }
            try {
                JSONObject json = JSON.parseObject(responseText);
                if (json == null) {
                    throw new EbayApiException("eBay API returned a malformed JSON response");
                }
                return json;
            } catch (JSONException e) {
                throw new EbayApiException("eBay API returned a malformed JSON response", e);
            }
        } catch (IOException e) {
            throw new EbayApiException("eBay API request failed due to a network error", e);
        }
    }

    private String responseText(ResponseBody body) throws IOException {
        return body == null ? "" : body.string();
    }

    private String requiredResponseField(JSONObject response, String field) {
        String value = response.getString(field);
        if (value == null || value.isBlank()) {
            throw new EbayApiException("eBay API response is missing " + field);
        }
        return value;
    }

    private HttpUrl inventoryUrl(String resource) {
        return inventoryBaseUrl.newBuilder().addPathSegment(resource).build();
    }

    private HttpUrl accountUrl(String resource) {
        return accountBaseUrl.newBuilder().addPathSegment(resource).build();
    }

    private HttpUrl requireHttpUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !("https".equals(parsed.scheme()) || isLoopbackHttp(parsed))) {
            throw new IllegalArgumentException("eBay API endpoint must use HTTPS");
        }
        return parsed;
    }

    private boolean isLoopbackHttp(HttpUrl url) {
        return "http".equals(url.scheme())
                && ("localhost".equals(url.host()) || "127.0.0.1".equals(url.host()));
    }

    private String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
