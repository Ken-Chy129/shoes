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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    public void createOrReplaceInventoryItemGroup(String inventoryItemGroupKey,
                                                   JSONObject payload,
                                                   String contentLanguage) {
        HttpUrl url = inventoryUrl("inventory_item_group").newBuilder()
                .addPathSegment(requireValue(inventoryItemGroupKey, "inventoryItemGroupKey"))
                .build();
        Request request = request(url, contentLanguage)
                .put(jsonBody(payload))
                .build();
        execute(request, Set.of(200, 204));
    }

    public Optional<JSONObject> getInventoryItemGroup(String inventoryItemGroupKey) {
        HttpUrl url = inventoryUrl("inventory_item_group").newBuilder()
                .addPathSegment(requireValue(inventoryItemGroupKey, "inventoryItemGroupKey"))
                .build();
        Request request = request(url, null).get().build();
        return Optional.ofNullable(execute(request, Set.of(200), true));
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

    public String publishOfferByInventoryItemGroup(String inventoryItemGroupKey,
                                                   String marketplaceId) {
        JSONObject payload = new JSONObject(true);
        payload.put("inventoryItemGroupKey",
                requireValue(inventoryItemGroupKey, "inventoryItemGroupKey"));
        payload.put("marketplaceId", requireValue(marketplaceId, "marketplaceId"));
        Request request = request(inventoryUrl("offer").newBuilder()
                .addPathSegment("publish_by_inventory_item_group")
                .build(), null)
                .post(jsonBody(payload))
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

    /**
     * Returns all active offers for the configured marketplace. The Inventory API
     * is paginated, so callers do not need to know the eBay page size.
     */
    public List<JSONObject> getActiveOffers(String marketplaceId) {
        return getOffers(builder -> builder
                .addQueryParameter("marketplace_id", requireValue(marketplaceId, "marketplaceId"))
                .addQueryParameter("listing_status", "ACTIVE"), false);
    }

    /**
     * Returns every offer associated with an inventory SKU, including
     * unpublished offers that can be safely reused instead of recreated.
     */
    public List<JSONObject> getOffersBySku(String sku) {
        return getOffers(builder -> builder
                .addQueryParameter("sku", requireValue(sku, "sku")), true);
    }

    private List<JSONObject> getOffers(Consumer<HttpUrl.Builder> filters,
                                       boolean unavailableOfferMeansEmpty) {
        List<JSONObject> offers = new ArrayList<>();
        int offset = 0;
        int limit = 200;
        while (true) {
            HttpUrl.Builder builder = inventoryUrl("offer").newBuilder();
            filters.accept(builder);
            HttpUrl url = builder
                    .addQueryParameter("limit", String.valueOf(limit))
                    .addQueryParameter("offset", String.valueOf(offset))
                    .build();
            JSONObject page = unavailableOfferMeansEmpty
                    ? execute(request(url, null).get().build(), Set.of(200), false, Set.of("25713"))
                    : get(url);
            if (page == null) {
                break;
            }
            var pageOffers = page.getJSONArray("offers");
            if (pageOffers == null || pageOffers.isEmpty()) {
                break;
            }
            for (int i = 0; i < pageOffers.size(); i++) {
                JSONObject offer = pageOffers.getJSONObject(i);
                if (offer != null) {
                    offers.add(offer);
                }
            }
            Integer total = page.getInteger("total");
            if (total != null ? offers.size() >= total : pageOffers.size() < limit) {
                break;
            }
            offset += pageOffers.size();
        }
        return List.copyOf(offers);
    }

    /**
     * Fetches an offer in its editable representation. This is useful because
     * updateOffer is a replacement request and must preserve the existing policies.
     */
    public JSONObject getOffer(String offerId) {
        HttpUrl url = inventoryUrl("offer").newBuilder()
                .addPathSegment(requireValue(offerId, "offerId"))
                .build();
        return get(url);
    }

    public void updateOffer(String offerId, JSONObject payload, String contentLanguage) {
        HttpUrl url = inventoryUrl("offer").newBuilder()
                .addPathSegment(requireValue(offerId, "offerId"))
                .build();
        Request request = request(url, contentLanguage)
                .put(jsonBody(payload))
                .build();
        execute(request, Set.of(200, 204));
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
        return execute(request, expectedStatusCodes, false);
    }

    private JSONObject execute(Request request, Set<Integer> expectedStatusCodes,
                               boolean nullOnNotFound) {
        return execute(request, expectedStatusCodes, nullOnNotFound, Set.of());
    }

    private JSONObject execute(Request request, Set<Integer> expectedStatusCodes,
                               boolean nullOnNotFound, Set<String> nullOnErrorIds) {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseText = responseText(response.body());
            if (response.code() == 404 && (nullOnNotFound
                    || nullOnErrorIds.contains(firstErrorId(responseText)))) {
                return null;
            }
            if (!expectedStatusCodes.contains(response.code())) {
                throw new EbayApiException("eBay API request failed (HTTP " + response.code()
                        + "): " + summarizeError(responseText));
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

    private String summarizeError(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return "empty response";
        }
        // Keep eBay's documented error code and text so task details are
        // actionable, while deliberately excluding parameters and request data
        // that can echo credentials or other sensitive values.
        if (responseText.trim().startsWith("{") || responseText.trim().startsWith("[")) {
            try {
                JSONObject response = JSON.parseObject(responseText);
                var errors = response == null ? null : response.getJSONArray("errors");
                JSONObject error = errors == null || errors.isEmpty()
                        ? null : errors.getJSONObject(0);
                if (error != null) {
                    String code = error.getString("errorId");
                    String message = firstNonBlank(
                            error.getString("longMessage"), error.getString("message"));
                    if (message != null) {
                        if (containsSensitiveMarker(message)) {
                            return code == null || code.isBlank()
                                    ? "provider error" : "eBay error " + code;
                        }
                        String summary = (code == null || code.isBlank() ? "" : code + ": ")
                                + message.replaceAll("\\s+", " ").trim();
                        return summary.length() <= 500 ? summary : summary.substring(0, 500);
                    }
                }
            } catch (JSONException ignored) {
                // Fall through to the generic label for malformed provider JSON.
            }
            return "provider error";
        }
        String compact = responseText.replaceAll("\\s+", " ").trim();
        compact = compact.replaceAll("(?i)(bearer\\s+|access-token[=:]\\s*)[^\\s,;]+", "$1[redacted]");
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private String firstErrorId(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        try {
            JSONObject response = JSON.parseObject(responseText);
            var errors = response == null ? null : response.getJSONArray("errors");
            JSONObject error = errors == null || errors.isEmpty()
                    ? null : errors.getJSONObject(0);
            return error == null ? null : error.getString("errorId");
        } catch (JSONException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private boolean containsSensitiveMarker(String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(access[-_ ]?token|refresh[-_ ]?token|bearer|client[-_ ]?secret).*?");
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
