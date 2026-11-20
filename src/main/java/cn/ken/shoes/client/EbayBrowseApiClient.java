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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * eBay Browse API（买家视角的公开商品信息），用应用令牌调用。
 * 只用来读刊登是否有正品保证（Authenticity Guarantee）：Trading API 没有这个字段，
 * 按卖家搜索的结果里也不带，只能逐条刊登读。额度为应用级 buy.browse，默认每天 5000 次。
 */
@Component
public class EbayBrowseApiClient {

    /** 刊登里的一个在售尺码：aspectValues 是它的属性值（含尺码），单品刊登只有一个且为空集合。 */
    public record BrowseVariation(Set<String> aspectValues, boolean authenticityGuarantee) {
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
     * 读一条刊登所有在售尺码的正品保证。多尺码刊登按商品组读（一次调用拿全部尺码），单品刊登按旧编号读。
     * 额度用完（HTTP 429）抛 {@link EbayQuotaExceededException}。
     */
    public List<BrowseVariation> getListingAuthenticity(String legacyItemId, boolean hasVariations) {
        HttpUrl url = hasVariations
                ? baseUrl.newBuilder().addPathSegment("item").addPathSegment("get_items_by_item_group")
                .addQueryParameter("item_group_id", legacyItemId).build()
                : baseUrl.newBuilder().addPathSegment("item").addPathSegment("get_item_by_legacy_id")
                .addQueryParameter("legacy_item_id", legacyItemId).build();
        JSONObject json = get(url);
        JSONArray items = hasVariations ? json.getJSONArray("items") : new JSONArray(List.of(json));
        List<BrowseVariation> result = new ArrayList<>();
        for (int i = 0; items != null && i < items.size(); i++) {
            result.add(parseItem(items.getJSONObject(i), hasVariations));
        }
        return result;
    }

    static BrowseVariation parseItem(JSONObject item, boolean withAspects) {
        Set<String> values = new LinkedHashSet<>();
        JSONArray aspects = withAspects ? item.getJSONArray("localizedAspects") : null;
        for (int i = 0; aspects != null && i < aspects.size(); i++) {
            String value = aspects.getJSONObject(i).getString("value");
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        JSONArray programs = item.getJSONArray("qualifiedPrograms");
        boolean guaranteed = item.get("authenticityGuarantee") != null
                || (programs != null && programs.contains("AUTHENTICITY_GUARANTEE"));
        return new BrowseVariation(values, guaranteed);
    }

    private JSONObject get(HttpUrl url) {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-EBAY-C-MARKETPLACE-ID", marketplaceId)
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
