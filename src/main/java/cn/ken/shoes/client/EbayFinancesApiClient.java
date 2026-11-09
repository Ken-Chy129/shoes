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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * eBay Finances API 只读调用（需要 sell.finances 授权）：读取订单实际产生的资金流水，
 * 包括销售入账（已扣成交费）、推广广告费、退款及费用返还。
 */
@Component
public class EbayFinancesApiClient {

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 200;
    private static final int MAX_ATTEMPTS = 3;
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;
    private final String marketplaceId;

    @Autowired
    public EbayFinancesApiClient(EbayProperties properties, EbayOAuthService oauthService) {
        this(oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getFinancesApiEndpoint(), properties.getDefaultMarketplaceId());
    }

    EbayFinancesApiClient(EbayOAuthService oauthService, OkHttpClient httpClient, String baseUrl,
                          String marketplaceId) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Finances API endpoint must use HTTPS");
        }
        this.baseUrl = parsed;
        this.marketplaceId = marketplaceId == null || marketplaceId.isBlank() ? "EBAY_US" : marketplaceId;
    }

    /** 按流水时间拉取全部资金流水（自动翻页）。 */
    public List<JSONObject> getTransactions(Instant from, Instant to) {
        String filter = "transactionDate:[" + ISO_MILLIS.format(from.truncatedTo(ChronoUnit.MILLIS))
                + ".." + ISO_MILLIS.format(to.truncatedTo(ChronoUnit.MILLIS)) + "]";
        List<JSONObject> transactions = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            HttpUrl url = baseUrl.newBuilder().addPathSegment("transaction")
                    .addQueryParameter("filter", filter)
                    .addQueryParameter("limit", String.valueOf(PAGE_SIZE))
                    .addQueryParameter("offset", String.valueOf(page * PAGE_SIZE))
                    .build();
            JSONObject response = get(url);
            JSONArray array = response.getJSONArray("transactions");
            if (array == null || array.isEmpty()) {
                break;
            }
            for (int i = 0; i < array.size(); i++) {
                transactions.add(array.getJSONObject(i));
            }
            if (array.size() < PAGE_SIZE || transactions.size() >= response.getIntValue("total")) {
                break;
            }
        }
        return transactions;
    }

    private JSONObject get(HttpUrl url) {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-EBAY-C-MARKETPLACE-ID", marketplaceId)
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
                    throw new EbayApiException("eBay Finances API无权限，请重新授权并勾选财务只读权限");
                }
                if (response.code() != 200) {
                    throw new EbayApiException("eBay Finances API失败(HTTP " + response.code() + ")");
                }
                JSONObject json = text.isBlank() ? new JSONObject() : JSON.parseObject(text);
                return json == null ? new JSONObject() : json;
            } catch (JSONException e) {
                throw new EbayApiException("eBay Finances API响应格式异常", e);
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                throw new EbayApiException("eBay Finances API网络异常", e);
            }
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EbayApiException("eBay Finances API retry interrupted", e);
        }
    }
}
