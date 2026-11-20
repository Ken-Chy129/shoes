package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayApplicationTokenService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * eBay Browse API（买家视角的公开商品信息），用应用令牌调用，额度为应用级 buy.browse（默认每天 5000 次）。
 * 只用来读刊登是否有正品保证（Authenticity Guarantee）：Trading API 没有这个字段。
 * 按卖家搜索一页 200 条，必须带上收货地（美国）才会返回 qualifiedPrograms。
 */
@Component
public class EbayBrowseApiClient {

    /** 搜索每页上限；eBay 搜索 offset + limit 不能超过 10000。 */
    public static final int SEARCH_PAGE_SIZE = 200;
    public static final int SEARCH_MAX_RESULTS = 10000;
    /** 服装鞋包大类：按卖家搜索必须带分类或关键词。 */
    private static final String SEARCH_CATEGORY = "11450";
    private static final String DELIVERY_FILTER = "deliveryCountry:US,deliveryPostalCode:10001";
    private static final String END_USER_CONTEXT = "contextualLocation=country%3DUS%2Czip%3D10001";

    /** 单条刊登：卖家账号 + 是否有正品保证（多尺码刊登任一在售尺码有即算有）。 */
    public record ListingAuthenticity(String seller, boolean authenticityGuarantee) {
    }

    /** 搜索的一页：total 为 eBay 给出的总条数，byItemId 为 旧物品编号 → 是否有正品保证。 */
    public record SearchPage(int total, Map<String, Boolean> byItemId) {
    }

    private final EbayApplicationTokenService tokenService;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;
    private final String marketplaceId;

    @Autowired
    public EbayBrowseApiClient(EbayProperties properties, EbayApplicationTokenService tokenService) {
        this(tokenService, new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getBrowseApiEndpoint(), properties.getDefaultMarketplaceId());
    }

    EbayBrowseApiClient(EbayApplicationTokenService tokenService, OkHttpClient httpClient, String baseUrl,
                        String marketplaceId) {
        this.tokenService = tokenService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Browse API endpoint must use HTTPS");
        }
        this.baseUrl = parsed;
        this.marketplaceId = marketplaceId;
    }

    /**
     * 读一条刊登（多尺码按商品组读，单品按旧编号读）。额度用完（HTTP 429）抛 {@link EbayQuotaExceededException}。
     */
    public ListingAuthenticity getListing(String legacyItemId, boolean hasVariations) {
        HttpUrl url = hasVariations
                ? baseUrl.newBuilder().addPathSegment("item").addPathSegment("get_items_by_item_group")
                .addQueryParameter("item_group_id", legacyItemId).build()
                : baseUrl.newBuilder().addPathSegment("item").addPathSegment("get_item_by_legacy_id")
                .addQueryParameter("legacy_item_id", legacyItemId).build();
        JSONObject json = get(url);
        JSONArray items = hasVariations ? json.getJSONArray("items") : new JSONArray(List.of(json));
        String seller = null;
        boolean guaranteed = false;
        for (int i = 0; items != null && i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject sellerJson = item.getJSONObject("seller");
            if (seller == null && sellerJson != null) {
                seller = sellerJson.getString("username");
            }
            guaranteed |= guaranteed(item);
        }
        return new ListingAuthenticity(seller, guaranteed);
    }

    /** 按卖家搜索一页（offset 从 0 开始，每页 {@link #SEARCH_PAGE_SIZE} 条）。 */
    public SearchPage searchSeller(String seller, int offset) {
        HttpUrl url = baseUrl.newBuilder().addPathSegment("item_summary").addPathSegment("search")
                .addQueryParameter("category_ids", SEARCH_CATEGORY)
                .addQueryParameter("filter", "sellers:{" + seller + "}," + DELIVERY_FILTER)
                .addQueryParameter("limit", String.valueOf(SEARCH_PAGE_SIZE))
                .addQueryParameter("offset", String.valueOf(offset))
                .build();
        return parseSearch(get(url));
    }

    static SearchPage parseSearch(JSONObject json) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        JSONArray summaries = json.getJSONArray("itemSummaries");
        for (int i = 0; summaries != null && i < summaries.size(); i++) {
            JSONObject summary = summaries.getJSONObject(i);
            String itemId = summary.getString("legacyItemId");
            if (itemId != null) {
                result.put(itemId, guaranteed(summary));
            }
        }
        return new SearchPage(json.getIntValue("total"), result);
    }

    private static boolean guaranteed(JSONObject item) {
        JSONArray programs = item.getJSONArray("qualifiedPrograms");
        return item.get("authenticityGuarantee") != null
                || (programs != null && programs.contains("AUTHENTICITY_GUARANTEE"));
    }

    private JSONObject get(HttpUrl url) {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-EBAY-C-MARKETPLACE-ID", marketplaceId)
                .header("X-EBAY-C-ENDUSERCTX", END_USER_CONTEXT)
                .header("Authorization", "Bearer " + tokenService.getValidAccessToken())
                .get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (response.code() == 429) {
                throw new EbayQuotaExceededException("eBay Browse API今日额度已用完(HTTP 429)");
            }
            if (response.code() != 200) {
                throw new EbayApiException("eBay Browse API请求失败(HTTP " + response.code() + ")");
            }
            JSONObject json = JSON.parseObject(text);
            if (json == null) {
                throw new EbayApiException("eBay Browse API返回为空");
            }
            return json;
        } catch (JSONException e) {
            throw new EbayApiException("eBay Browse API响应格式异常", e);
        } catch (IOException e) {
            throw new EbayApiException("eBay Browse API网络异常", e);
        }
    }
}
