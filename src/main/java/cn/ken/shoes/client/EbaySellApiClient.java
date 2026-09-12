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
import java.util.Collection;
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

    /**
     * 删除商品组本身，组内的库存项与 offer 全部保留。
     * eBay 侧偶发会把某个组的服务端状态弄坏，之后整组发布只回 25001，
     * 删掉再用同一个 key 重建即可恢复。组不存在时按已删除处理。
     */
    public void deleteInventoryItemGroup(String inventoryItemGroupKey) {
        HttpUrl url = inventoryUrl("inventory_item_group").newBuilder()
                .addPathSegment(requireValue(inventoryItemGroupKey, "inventoryItemGroupKey"))
                .build();
        Request request = request(url, null).delete().build();
        execute(request, Set.of(200, 204), true);
    }

    /** 读取单个库存项；SKU 不存在时返回空。 */
    public Optional<JSONObject> getInventoryItem(String sku) {
        HttpUrl url = inventoryUrl("inventory_item").newBuilder()
                .addPathSegment(requireValue(sku, "sku"))
                .build();
        Request request = request(url, null).get().build();
        return Optional.ofNullable(execute(request, Set.of(200), true));
    }

    /**
     * 将一个库存项的可售数量改为指定值，同时保留 eBay 已保存的商品资料。
     * Inventory Item 的 PUT 是整对象替换，不能只发送 quantity。
     *
     * @return 修改前的库存数量
     */
    public int updateInventoryItemQuantity(String sku, int quantity, String contentLanguage) {
        if (quantity < 0) {
            throw new IllegalArgumentException("inventory quantity cannot be negative");
        }
        JSONObject current = getInventoryItem(sku)
                .orElseThrow(() -> new EbayApiException("eBay inventory item does not exist: " + sku));
        JSONObject payload = new JSONObject(true);
        for (String field : List.of("condition", "conditionDescription",
                "packageWeightAndSize", "product")) {
            if (current.containsKey(field)) {
                payload.put(field, current.get(field));
            }
        }
        JSONObject availability = current.getJSONObject("availability");
        if (availability == null) {
            availability = new JSONObject(true);
        } else {
            availability = JSONObject.parseObject(availability.toJSONString());
        }
        payload.put("availability", availability);
        JSONObject shipAvailability = availability.getJSONObject("shipToLocationAvailability");
        if (shipAvailability == null) {
            shipAvailability = new JSONObject(true);
            availability.put("shipToLocationAvailability", shipAvailability);
        }
        int previousQuantity = shipAvailability.getIntValue("quantity");
        shipAvailability.put("quantity", quantity);
        createOrReplaceInventoryItem(sku, payload, contentLanguage);
        return previousQuantity;
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
    /**
     * 读取这些 SKU 下仍在售的 offer。
     *
     * <p>eBay 的 {@code GET /offer} 必须带 SKU：只按站点和 listing_status
     * 过滤会被拒绝（错误码 25707），因此枚举在架商品只能逐个 SKU 查询。
     * 单个 SKU 查询失败不会中断整批，保证一个坏 SKU 不影响其余商品。
     */
    public List<JSONObject> getActiveOffersBySkus(Collection<String> skus) {
        List<JSONObject> offers = new ArrayList<>();
        for (String sku : skus == null ? List.<String>of() : skus) {
            if (sku == null || sku.isBlank()) {
                continue;
            }
            for (JSONObject offer : getOffersBySku(sku)) {
                if (isActiveOffer(offer)) {
                    offers.add(offer);
                }
            }
        }
        return List.copyOf(offers);
    }

    /**
     * 判断 offer 是否仍占用一个在售 listing。已结束或从未发布的返回 false。
     */
    public boolean isActiveOffer(JSONObject offer) {
        if (offer == null) {
            return false;
        }
        JSONObject listing = offer.getJSONObject("listing");
        String listingStatus = listing == null ? null : listing.getString("listingStatus");
        if (listingStatus != null) {
            return "ACTIVE".equalsIgnoreCase(listingStatus)
                    || "OUT_OF_STOCK".equalsIgnoreCase(listingStatus);
        }
        return "PUBLISHED".equalsIgnoreCase(offer.getString("status"));
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

    /**
     * 分页读取账号下所有库存项的 SKU。
     *
     * <p>eBay 的 {@code GET /offer} 只支持按 SKU 查询，按站点直接列出
     * 全部 offer 会被拒绝（错误码 25707），所以枚举在架商品必须先取
     * 库存项 SKU，再用 {@link #getOffersBySku(String)} 逐个查 offer。
     */
    public List<String> getInventoryItemSkus() {
        List<String> skus = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        while (true) {
            HttpUrl url = inventoryUrl("inventory_item").newBuilder()
                    .addQueryParameter("limit", String.valueOf(limit))
                    .addQueryParameter("offset", String.valueOf(offset))
                    .build();
            JSONObject page = get(url);
            if (page == null) {
                break;
            }
            var items = page.getJSONArray("inventoryItems");
            if (items == null || items.isEmpty()) {
                break;
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                String sku = item == null ? null : item.getString("sku");
                if (sku != null && !sku.isBlank()) {
                    skus.add(sku);
                }
            }
            Integer total = page.getInteger("total");
            if (total != null ? skus.size() >= total : items.size() < limit) {
                break;
            }
            offset += items.size();
        }
        return List.copyOf(skus);
    }

    /**
     * 结束单个 offer 对应的在架 listing，但保留 offer 与库存数据，
     * 便于之后重新上架。eBay 对已结束或未发布的 offer 返回 25002/25004，
     * 这里视为「已下架」而不是失败。
     */
    public boolean withdrawOffer(String offerId) {
        HttpUrl url = inventoryUrl("offer").newBuilder()
                .addPathSegment(requireValue(offerId, "offerId"))
                .addPathSegment("withdraw")
                .build();
        Request request = request(url, null)
                .post(EMPTY_JSON_BODY)
                .build();
        try {
            execute(request, Set.of(200, 204));
            return true;
        } catch (EbayApiException e) {
            if (isAlreadyWithdrawn(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * eBay 在 offer 已经结束或从未发布时返回这些错误码，对下架来说是幂等成功。
     * 25082 表示该变体已不在 listing 上（库存置 0 后被 eBay 移除），同样无需再下架。
     */
    private boolean isAlreadyWithdrawn(EbayApiException error) {
        String message = error.getMessage();
        return message != null && (message.contains("25002:")
                || message.contains("25004:")
                || message.contains("25044:")
                || message.contains("25082:")
                || message.contains("25801:"));
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
