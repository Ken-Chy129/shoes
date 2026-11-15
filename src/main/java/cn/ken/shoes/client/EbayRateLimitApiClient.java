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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * eBay Developer Analytics API 的 getRateLimits：查询应用级别各 API 的额度、已用次数和重置时间。
 * 用应用令牌(client credentials)调用，本身不消耗 Trading API 额度。
 */
@Component
public class EbayRateLimitApiClient {

    /** 某个接口（resource）的额度情况。Trading API 的大部分接口共用同一个池子，remaining 相同。 */
    public record ResourceRate(String name, long count, long limit, long remaining, Instant reset,
                               long timeWindowSeconds) {
    }

    private final EbayApplicationTokenService tokenService;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;

    @Autowired
    public EbayRateLimitApiClient(EbayProperties properties, EbayApplicationTokenService tokenService) {
        this(tokenService, new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getDeveloperAnalyticsApiEndpoint());
    }

    EbayRateLimitApiClient(EbayApplicationTokenService tokenService, OkHttpClient httpClient, String baseUrl) {
        this.tokenService = tokenService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Developer Analytics API endpoint must use HTTPS");
        }
        this.baseUrl = parsed;
    }

    /** 指定 API（如 TradingAPI）下所有接口的额度，按已用次数从多到少排序。 */
    public List<ResourceRate> getRateLimits(String apiName) {
        HttpUrl url = baseUrl.newBuilder().addPathSegment("rate_limit").addPathSegment("")
                .addQueryParameter("api_name", apiName)
                .build();
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokenService.getValidAccessToken())
                .get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (response.code() != 200) {
                throw new EbayApiException("eBay getRateLimits失败(HTTP " + response.code() + ")");
            }
            JSONObject json = JSON.parseObject(text);
            List<ResourceRate> result = new ArrayList<>();
            JSONArray apis = json == null ? null : json.getJSONArray("rateLimits");
            for (int i = 0; apis != null && i < apis.size(); i++) {
                JSONArray resources = apis.getJSONObject(i).getJSONArray("resources");
                for (int j = 0; resources != null && j < resources.size(); j++) {
                    JSONObject resource = resources.getJSONObject(j);
                    JSONArray rates = resource.getJSONArray("rates");
                    if (rates == null || rates.isEmpty()) {
                        continue;
                    }
                    JSONObject rate = rates.getJSONObject(0);
                    result.add(new ResourceRate(resource.getString("name"), rate.getLongValue("count"),
                            rate.getLongValue("limit"), rate.getLongValue("remaining"),
                            parseInstant(rate.getString("reset")), rate.getLongValue("timeWindow")));
                }
            }
            result.sort(Comparator.comparingLong(ResourceRate::count).reversed()
                    .thenComparing(ResourceRate::name, Comparator.nullsLast(Comparator.naturalOrder())));
            return result;
        } catch (JSONException e) {
            throw new EbayApiException("eBay getRateLimits响应格式异常", e);
        } catch (IOException e) {
            throw new EbayApiException("eBay getRateLimits网络异常", e);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
