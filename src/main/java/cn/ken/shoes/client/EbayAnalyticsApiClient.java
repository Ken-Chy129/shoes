package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayOAuthService;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * eBay Analytics API 只读调用（需要 sell.analytics.readonly 授权）：按刊登读取浏览量。
 * eBay 流量报表最多回溯 90 天，所以「浏览人数」是最近 N 天（≤90）的浏览次数，不是刊登以来的总数。
 */
@Component
public class EbayAnalyticsApiClient {

    /** traffic_report 的 listing_ids 过滤一次最多 200 个。 */
    public static final int LISTING_BATCH = 200;
    public static final int MAX_DAYS = 90;
    private static final int MAX_ATTEMPTS = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;

    @Autowired
    public EbayAnalyticsApiClient(EbayProperties properties, EbayOAuthService oauthService) {
        this(oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getAnalyticsApiEndpoint());
    }

    EbayAnalyticsApiClient(EbayOAuthService oauthService, OkHttpClient httpClient, String baseUrl) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Analytics API endpoint must use HTTPS");
        }
        this.baseUrl = parsed;
    }

    /**
     * listingId -> [from, to] 日期区间内的浏览次数（LISTING_VIEWS_TOTAL）。没有浏览记录的刊登不在结果里。
     * 一次最多 {@link #LISTING_BATCH} 个刊登。
     */
    public Map<String, Integer> getListingViews(Collection<String> listingIds, LocalDate from, LocalDate to,
                                                String marketplaceId) {
        Map<String, Integer> views = new HashMap<>();
        if (listingIds == null || listingIds.isEmpty()) {
            return views;
        }
        if (listingIds.size() > LISTING_BATCH) {
            throw new IllegalArgumentException("单次最多查询" + LISTING_BATCH + "个刊登的浏览量");
        }
        String filter = "marketplace_ids:{" + marketplaceId + "},"
                + "date_range:[" + DATE.format(from) + ".." + DATE.format(to) + "],"
                + "listing_ids:{" + String.join("|", listingIds) + "}";
        HttpUrl url = baseUrl.newBuilder().addPathSegment("traffic_report")
                .addQueryParameter("dimension", "LISTING")
                .addQueryParameter("metric", "LISTING_VIEWS_TOTAL")
                .addQueryParameter("filter", filter)
                .build();
        JSONObject report = get(url);
        JSONArray records = report.getJSONArray("records");
        for (int i = 0; records != null && i < records.size(); i++) {
            JSONObject record = records.getJSONObject(i);
            JSONArray dimensions = record.getJSONArray("dimensionValues");
            JSONArray metrics = record.getJSONArray("metricValues");
            if (dimensions == null || dimensions.isEmpty() || metrics == null || metrics.isEmpty()) {
                continue;
            }
            String listingId = dimensions.getJSONObject(0).getString("value");
            Integer value = metrics.getJSONObject(0).getInteger("value");
            if (listingId != null && value != null) {
                views.merge(listingId, value, Integer::sum);
            }
        }
        return views;
    }

    /** 把刊登号分成每批 {@link #LISTING_BATCH} 个。 */
    public static List<List<String>> batches(List<String> listingIds) {
        List<List<String>> result = new java.util.ArrayList<>();
        for (int i = 0; i < listingIds.size(); i += LISTING_BATCH) {
            result.add(listingIds.subList(i, Math.min(listingIds.size(), i + LISTING_BATCH)));
        }
        return result;
    }

    private JSONObject get(HttpUrl url) {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + oauthService.getValidAccessToken())
                .get().build();
        for (int attempt = 1; ; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String text = body == null ? "" : body.string();
                if ((response.code() >= 500 || response.code() == 429) && attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                if (response.code() == 403) {
                    throw new EbayApiException("eBay Analytics API无权限，请在系统设置里重新授权eBay");
                }
                if (response.code() != 200) {
                    throw new EbayApiException("eBay Analytics API失败(HTTP " + response.code() + ")");
                }
                JSONObject json = text.isBlank() ? new JSONObject() : JSON.parseObject(text);
                return json == null ? new JSONObject() : json;
            } catch (JSONException e) {
                throw new EbayApiException("eBay Analytics API响应格式异常", e);
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                throw new EbayApiException("eBay Analytics API网络异常", e);
            }
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EbayApiException("eBay Analytics API retry interrupted", e);
        }
    }
}
