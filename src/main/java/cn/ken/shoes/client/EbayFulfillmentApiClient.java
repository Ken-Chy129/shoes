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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * eBay Fulfillment API 只读调用（需要 sell.fulfillment.readonly 授权）。
 * 用来补充 Trading API 拿不到的买家地址、课税地址、促销标记与准确的发货时间。
 */
@Component
public class EbayFulfillmentApiClient {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;

    @Autowired
    public EbayFulfillmentApiClient(EbayProperties properties, EbayOAuthService oauthService) {
        this(oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getFulfillmentApiEndpoint());
    }

    EbayFulfillmentApiClient(EbayOAuthService oauthService, OkHttpClient httpClient, String baseUrl) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Fulfillment API endpoint must use HTTPS");
        }
        this.baseUrl = parsed;
    }

    /** 按创建时间拉取全部订单（自动翻页）。 */
    public List<JSONObject> getOrders(Instant createdFrom, Instant createdTo) {
        String filter = "creationdate:[" + ISO_MILLIS.format(createdFrom.truncatedTo(ChronoUnit.MILLIS))
                + ".." + ISO_MILLIS.format(createdTo.truncatedTo(ChronoUnit.MILLIS)) + "]";
        List<JSONObject> orders = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            HttpUrl url = baseUrl.newBuilder().addPathSegment("order")
                    .addQueryParameter("filter", filter)
                    .addQueryParameter("limit", String.valueOf(PAGE_SIZE))
                    .addQueryParameter("offset", String.valueOf(page * PAGE_SIZE))
                    .build();
            JSONObject response = get(url);
            JSONArray array = response.getJSONArray("orders");
            if (array == null || array.isEmpty()) {
                break;
            }
            for (int i = 0; i < array.size(); i++) {
                orders.add(array.getJSONObject(i));
            }
            int total = response.getIntValue("total");
            if (array.size() < PAGE_SIZE || orders.size() >= total) {
                break;
            }
        }
        return orders;
    }

    /** 订单的全部发货记录（含 shippedDate、lineItems）。 */
    public List<JSONObject> getShippingFulfillments(String orderId) {
        HttpUrl url = baseUrl.newBuilder().addPathSegment("order").addPathSegment(orderId)
                .addPathSegment("shipping_fulfillment").build();
        JSONArray array = get(url).getJSONArray("fulfillments");
        List<JSONObject> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                result.add(array.getJSONObject(i));
            }
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
                if (response.code() >= 500 && attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                if (response.code() != 200) {
                    throw new EbayApiException("eBay Fulfillment API失败(HTTP " + response.code() + ")");
                }
                JSONObject json = text.isBlank() ? new JSONObject() : JSON.parseObject(text);
                return json == null ? new JSONObject() : json;
            } catch (JSONException e) {
                throw new EbayApiException("eBay Fulfillment API响应格式异常", e);
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                throw new EbayApiException("eBay Fulfillment API网络异常", e);
            }
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EbayApiException("eBay Fulfillment API retry interrupted", e);
        }
    }
}
