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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * eBay Marketing API 只读调用（需要 sell.marketing.readonly 授权）：读取运行中的
 * 按成交付费（Promoted Listings Standard）广告活动里每个商品设置的广告费率。
 */
@Component
public class EbayMarketingApiClient {

    private static final int CAMPAIGN_PAGE_SIZE = 100;
    private static final int AD_PAGE_SIZE = 500;
    private static final int MAX_PAGES = 1000;
    private static final int MAX_ATTEMPTS = 3;

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;

    @Autowired
    public EbayMarketingApiClient(EbayProperties properties, EbayOAuthService oauthService) {
        this(oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getMarketingApiEndpoint());
    }

    EbayMarketingApiClient(EbayOAuthService oauthService, OkHttpClient httpClient, String baseUrl) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Marketing API endpoint must use HTTPS");
        }
        this.baseUrl = parsed;
    }

    /**
     * listingId -> 广告费率（百分比数值，例如 4.0 表示 4%）。
     *
     * <p>只统计运行中的按成交付费活动；按点击付费的活动不按成交收费，不计入。
     * 商品自身未设置费率时取活动的统一费率；同一商品在多个活动里时取最高费率（保守估算成本）。
     */
    public Map<String, BigDecimal> getRunningAdRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        for (JSONObject campaign : getRunningCampaigns()) {
            JSONObject funding = campaign.getJSONObject("fundingStrategy");
            if (funding == null || !"COST_PER_SALE".equalsIgnoreCase(funding.getString("fundingModel"))) {
                continue;
            }
            BigDecimal campaignRate = decimal(funding.getString("bidPercentage"));
            String campaignId = campaign.getString("campaignId");
            if (campaignId == null) {
                continue;
            }
            for (int page = 0; page < MAX_PAGES; page++) {
                HttpUrl url = baseUrl.newBuilder().addPathSegment("ad_campaign").addPathSegment(campaignId)
                        .addPathSegment("ad")
                        .addQueryParameter("limit", String.valueOf(AD_PAGE_SIZE))
                        .addQueryParameter("offset", String.valueOf(page * AD_PAGE_SIZE))
                        .build();
                JSONObject response = get(url);
                JSONArray ads = response.getJSONArray("ads");
                if (ads == null || ads.isEmpty()) {
                    break;
                }
                for (int i = 0; i < ads.size(); i++) {
                    JSONObject ad = ads.getJSONObject(i);
                    String listingId = ad.getString("listingId");
                    String status = ad.getString("adStatus");
                    if (listingId == null || (status != null && !"ACTIVE".equalsIgnoreCase(status))) {
                        continue;
                    }
                    BigDecimal rate = decimal(ad.getString("bidPercentage"));
                    if (rate == null) {
                        rate = campaignRate;
                    }
                    if (rate != null) {
                        rates.merge(listingId, rate, BigDecimal::max);
                    }
                }
                if (ads.size() < AD_PAGE_SIZE || (page + 1) * AD_PAGE_SIZE >= response.getIntValue("total")) {
                    break;
                }
            }
        }
        return rates;
    }

    private List<JSONObject> getRunningCampaigns() {
        List<JSONObject> campaigns = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            HttpUrl url = baseUrl.newBuilder().addPathSegment("ad_campaign")
                    .addQueryParameter("campaign_status", "RUNNING")
                    .addQueryParameter("limit", String.valueOf(CAMPAIGN_PAGE_SIZE))
                    .addQueryParameter("offset", String.valueOf(page * CAMPAIGN_PAGE_SIZE))
                    .build();
            JSONObject response = get(url);
            JSONArray array = response.getJSONArray("campaigns");
            if (array == null || array.isEmpty()) {
                break;
            }
            for (int i = 0; i < array.size(); i++) {
                JSONObject campaign = array.getJSONObject(i);
                // 服务端已按状态过滤，这里再确认一次，避免把已结束活动的费率算进成本。
                if ("RUNNING".equalsIgnoreCase(campaign.getString("campaignStatus"))) {
                    campaigns.add(campaign);
                }
            }
            if (array.size() < CAMPAIGN_PAGE_SIZE
                    || (page + 1) * CAMPAIGN_PAGE_SIZE >= response.getIntValue("total")) {
                break;
            }
        }
        return campaigns;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
                    throw new EbayApiException("eBay Marketing API无权限，请重新授权并勾选营销活动只读权限");
                }
                if (response.code() != 200) {
                    throw new EbayApiException("eBay Marketing API失败(HTTP " + response.code() + ")");
                }
                JSONObject json = text.isBlank() ? new JSONObject() : JSON.parseObject(text);
                return json == null ? new JSONObject() : json;
            } catch (JSONException e) {
                throw new EbayApiException("eBay Marketing API响应格式异常", e);
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                throw new EbayApiException("eBay Marketing API网络异常", e);
            }
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EbayApiException("eBay Marketing API retry interrupted", e);
        }
    }
}
