package cn.ken.shoes.client;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.KickScrewApiConstant;
import cn.ken.shoes.common.PoisonApiConstant;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.service.ConfigService;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SignUtil;
import com.alibaba.fastjson.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PoisonClient {

    @Value("${poison.url:}")
    private String url;

    @Value("${poison.appKey}")
    private String appKey;

    @Value("${poison.appSecret}")
    private String appSecret;

    @Value("${poison.token:}")
    private String token;

    @Value("${poison.tokenV2:}")
    private String tokenV2;

    @Value("${poison.v3Url:}")
    private String v3Url;

    @Value("${poison.tokenV3:}")
    private String tokenV3;

    @Value("${poison.v4Code:}")
    private String v4Code;

    @Value("${poison.popUrl:https://open.poizon.com/dop/api/v1/pop/api/v1/market/channel}")
    private String popUrl;

    @Value("${poison.popAccessToken:}")
    private String popAccessToken;

    @Value("${poison.popLanguage:zh}")
    private String popLanguage;

    @Value("${poison.popTimeZone:Asia/Shanghai}")
    private String popTimeZone;

    @Value("${poison.popRedirectUri:https://shoes.ken-chy129.cn/poison/callback}")
    private String popRedirectUri;

    @Resource
    private ConfigService configService;

    private static final String POP_OAUTH_CONFIG_FILE = "poison-oauth.properties";
    private static final String POP_TOKEN_URL = "https://open.poizon.com/api/v1/h5/passport/v1/oauth2/token";
    private static final String POP_REFRESH_TOKEN_URL = "https://open.poizon.com/api/v1/h5/passport/v1/oauth2/refresh_token";
    private static final long TOKEN_REFRESH_BUFFER_MS = 10 * 60 * 1000L;

    private String popRefreshToken;
    private long popAccessTokenExpiresAt;
    private long popRefreshTokenExpiresAt;
    private String popOpenId;

    private final ConcurrentHashMap<String, Long> spuIdCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadPopOAuthToken() {
        Properties properties = configService.loadConfig(POP_OAUTH_CONFIG_FILE);
        popAccessToken = properties.getProperty("access.token", popAccessToken);
        popRefreshToken = properties.getProperty("refresh.token", popRefreshToken);
        popOpenId = properties.getProperty("open.id", popOpenId);
        popAccessTokenExpiresAt = parseLong(properties.getProperty("access.token.expires.at"), 0L);
        popRefreshTokenExpiresAt = parseLong(properties.getProperty("refresh.token.expires.at"), 0L);
    }

    public List<PoisonPriceDO> batchQueryPrice(List<String> modelNos) {
        if (CollectionUtils.isEmpty(modelNos)) {
            return Collections.emptyList();
        }
        return batchQueryPriceByDistApi(modelNos);
    }

    public List<PoisonPriceDO> queryPriceByModelNo(String modelNo) {
        if (modelNo == null) {
            return Collections.emptyList();
        }
        return queryPriceByDistApi(modelNo);
    }

    public List<PoisonPriceDO> queryPriceByModelNoV2(String modelNo) {
        // 已记录为得物无价的货号
        if (ShoesContext.isNoPrice(modelNo)) {
            return Collections.emptyList();
        }
        // 重试
        int times = 0;
        String result;
        do {
            result = doQueryPriceByModelNo(PoisonApiConstant.PRICE_BY_MODEL_NO_V2, modelNo, tokenV2);
            times++;
        } while (result == null && times <= 3);
        if (result == null) {
            log.error("queryPriceByModelNoV2 error, no result:{}", modelNo);
            return Collections.emptyList();
        }
        JSONObject jsonObject = JSON.parseObject(result);
        JSONObject priceData = jsonObject.getJSONObject("price_data");
        if (priceData == null) {
            ShoesContext.addNoPrice(modelNo);
            return Collections.emptyList();
        }
        Date time = jsonObject.getDate("time");
        LocalDate update = LocalDate.ofInstant(time.toInstant(), ZoneId.systemDefault());
        LocalDate threeDayAgo = LocalDate.now().minusDays(3);
        if (update.isBefore(threeDayAgo)) {
            log.error("queryPriceByModelNoV2 data is too old, update:{}", update);
            return Collections.emptyList();
        }
        List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
        for (String euSize: priceData.keySet()) {
            JSONObject priceObject = priceData.getJSONObject(euSize);
            if (priceObject == null) {
                continue;
            }
            Integer price = priceObject.getInteger("price");
            if (price == null || price <= 0 || price > PoisonSwitch.MAX_PRICE) {
                continue;
            }
            PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
            poisonPriceDO.setModelNo(modelNo);
            poisonPriceDO.setEuSize(ShoesUtil.normalizeUnicodeFraction(euSize));
            poisonPriceDO.setPrice(price);
            poisonPriceDO.setUpdateTime(time);
            poisonPriceDOList.add(poisonPriceDO);
        }
        return poisonPriceDOList;
    }

    public List<PoisonPriceDO> queryPriceByModelNoV1(String modelNo) {
        // 已记录为得物无价的货号
        if (ShoesContext.isNoPrice(modelNo)) {
            return Collections.emptyList();
        }
        // 重试
        int times = 0;
        String result;
        do {
            result = doQueryPriceByModelNo(PoisonApiConstant.PRICE_BY_MODEL_NO, modelNo, token);
            times++;
        } while (result == null && times <= 3);
        if (result == null) {
            log.error("queryPriceByModelNo error, no result:{}", modelNo);
            return Collections.emptyList();
        }
        if ("{}".equals(result)) {
            ShoesContext.addNoPrice(modelNo);
            return Collections.emptyList();
        }
        JSONObject jsonObject = JSON.parseObject(result);
        LocalDate update = LocalDate.ofInstant(jsonObject.getDate("update").toInstant(), ZoneId.systemDefault());
        LocalDate threeDayAgo = LocalDate.now().minusDays(3);
        if (update.isBefore(threeDayAgo)) {
            log.error("queryPriceByModelNo data is too old, update:{}", update);
            return Collections.emptyList();
        }
        List<JSONObject> dataList = jsonObject.getJSONArray("data").toJavaList(JSONObject.class);
        List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
        Set<String> sizeSet = new HashSet<>();
        for (JSONObject data : dataList) {
            Integer price = data.getInteger("minprice");
            String size = ShoesUtil.getShoesSizeFrom(data.getString("size"));
            Date dataUpdate = jsonObject.getDate("update");
            if (dataUpdate == null) {
                continue;
            }
            if (LocalDate.ofInstant(dataUpdate.toInstant(), ZoneId.systemDefault()).isBefore(threeDayAgo)) {
                continue;
            }
            if (price == null || price == 0 || size == null || sizeSet.contains(size) || price > 100 * PoisonSwitch.MAX_PRICE) {
                continue;
            } else {
                sizeSet.add(size);
            }
            PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
            poisonPriceDO.setModelNo(modelNo);
            poisonPriceDO.setEuSize(size);
            poisonPriceDO.setPrice(price / 100);
            poisonPriceDO.setUpdateTime(dataUpdate);
            poisonPriceDOList.add(poisonPriceDO);
        }
        return poisonPriceDOList;
    }

    private String doQueryPriceByModelNo(String baseUrl, String modelNo, String token) {
        LimiterHelper.limitPoisonPrice();
        String url = baseUrl
                .replace("{modelNo}", modelNo)
                .replace("{token}", token);
        return HttpUtil.doGet(url, false);
    }

    public List<PoisonPriceDO> queryPriceBySpuV2(String modelNo, Long spuId) {
        if (PoisonSwitch.USE_V4_API) {
            return queryPriceByModelNoV4(modelNo);
        }
        if (PoisonSwitch.USE_V3_API) {
            return queryPriceV3(modelNo, spuId);
        }
        String url = PoisonApiConstant.PRICE_BY_SPU
                .replace("{spuId}", String.valueOf(spuId))
                .replace("{token}", token);
        String result = HttpUtil.doGet(url);
        try {
            if ("{}".equals(result)) {
                return Collections.emptyList();
            }
            JSONArray dataJson = JSON.parseObject(result).getJSONArray("data");
            List<JSONObject> dataList = dataJson.toJavaList(JSONObject.class);
            List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
            Set<String> sizeSet = new HashSet<>();
            for (JSONObject data : dataList) {
                Integer price = data.getInteger("minprice");
                String size = ShoesUtil.getShoesSizeFrom(data.getString("size"));
                if (price == null || price == 0 || size == null || sizeSet.contains(size) || price > 100 * PoisonSwitch.MAX_PRICE) {
                    continue;
                } else {
                    sizeSet.add(size);
                }
                PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                poisonPriceDO.setModelNo(modelNo);
                poisonPriceDO.setEuSize(size);
                poisonPriceDO.setPrice(price / 100);
                poisonPriceDOList.add(poisonPriceDO);
            }
            return poisonPriceDOList;
        } catch (Exception e) {
            log.error("queryPriceBySpuV2 error, msg:{}, model:{}, spuId:{}", e.getMessage(), modelNo, spuId);
            return Collections.emptyList();
        }
    }

    // ========== Dist API Methods (官方分销开放API) ==========

    public List<PoisonPriceDO> queryPriceByDistApi(String modelNo) {
        if (ShoesContext.isNoPrice(modelNo)) {
            return Collections.emptyList();
        }
        List<PoisonPriceDO> result = batchQueryPriceByDistApi(List.of(modelNo));
        if (result.isEmpty()) {
            ShoesContext.addNoPrice(modelNo);
        }
        return result;
    }

    public List<PoisonPriceDO> batchQueryPriceByDistApi(List<String> modelNos) {
        List<PoisonPriceDO> allPrices = new ArrayList<>();
        List<List<String>> batches = com.google.common.collect.Lists.partition(modelNos, 200);
        for (List<String> batch : batches) {
            LimiterHelper.limitPoisonPrice();
            JSONObject params = new JSONObject();
            params.put("dwDesignerId", batch);
            params.put("pageSize", 200);
            params.put("querySku", true);
            enhancePopParams(params);
            String result = HttpUtil.doPost(popUrl + PoisonApiConstant.POP_QUERY_SPU_LIST,
                    params.toJSONString(), buildHeaders());
            if (result == null) {
                log.error("batchQueryPriceByDistApi error, no result, modelNos:{}", batch);
                continue;
            }
            try {
                JSONObject json = JSON.parseObject(result);
                Integer code = json.getInteger("code");
                if (code == null || code != 200) {
                    log.error("batchQueryPriceByDistApi error, code:{}, msg:{}", code, json.getString("msg"));
                    continue;
                }
                JSONObject data = json.getJSONObject("data");
                if (data == null) {
                    continue;
                }
                JSONArray spuList = data.getJSONArray("spuList");
                if (spuList == null || spuList.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < spuList.size(); i++) {
                    JSONObject spu = spuList.getJSONObject(i);
                    String articleNumber = spu.getString("dwDesignerId");
                    JSONArray skuList = spu.getJSONArray("skuList");
                    if (skuList == null) {
                        continue;
                    }
                    for (int j = 0; j < skuList.size(); j++) {
                        JSONObject sku = skuList.getJSONObject(j);
                        Long minBidPrice = sku.getLong("minBidPrice");
                        if (minBidPrice == null || minBidPrice <= 0 || minBidPrice > PoisonSwitch.MAX_PRICE * 100L) {
                            continue;
                        }
                        String euSize = ShoesUtil.normalizeUnicodeFraction(extractDistApiSize(sku));
                        if (euSize == null) {
                            continue;
                        }
                        PoisonPriceDO priceDO = new PoisonPriceDO();
                        priceDO.setModelNo(articleNumber);
                        priceDO.setEuSize(euSize);
                        priceDO.setPrice((int) (minBidPrice / 100));
                        priceDO.setUpdateTime(new Date());
                        allPrices.add(priceDO);
                    }
                }
            } catch (Exception e) {
                log.error("batchQueryPriceByDistApi parse error, msg:{}", e.getMessage());
            }
        }
        return allPrices;
    }

    private String extractDistApiSize(JSONObject sku) {
        JSONArray saleAttr = sku.getJSONArray("saleAttr");
        if (saleAttr != null) {
            for (int i = 0; i < saleAttr.size(); i++) {
                JSONObject attr = saleAttr.getJSONObject(i);
                if ("Size".equals(attr.getString("enName"))) {
                    return attr.getString("enValue");
                }
            }
        }
        JSONArray sizeInfos = sku.getJSONArray("sizeInfos");
        if (sizeInfos != null) {
            for (int i = 0; i < sizeInfos.size(); i++) {
                JSONObject info = sizeInfos.getJSONObject(i);
                if (info.getString("sizeKey") != null && info.getString("sizeKey").contains("EU")) {
                    return info.getString("sizeValue");
                }
            }
        }
        return null;
    }

    // ========== V3 API Methods ==========

    public List<PoisonPriceDO> queryPriceByModelNoV3(String modelNo) {
        if (ShoesContext.isNoPrice(modelNo)) {
            return Collections.emptyList();
        }
        Long spuId = spuIdCache.get(modelNo);
        if (spuId == null) {
            spuId = searchSpuIdV3(modelNo);
            if (spuId == null) {
                ShoesContext.addNoPrice(modelNo);
                return Collections.emptyList();
            }
            spuIdCache.put(modelNo, spuId);
        }
        int times = 0;
        List<PoisonPriceDO> prices;
        do {
            prices = queryPriceV3(modelNo, spuId);
            times++;
        } while (prices.isEmpty() && times <= 3);
        return prices;
    }

    private Long searchSpuIdV3(String keyword) {
        LimiterHelper.limitPoisonPrice();
        String searchUrl = v3Url + PoisonApiConstant.V3_SEARCH_PRODUCT + "?keyword=" + keyword;
        String result = HttpUtil.doGet(searchUrl, buildV3Headers(), false);
        if (result == null) {
            log.error("searchSpuIdV3 error, no result, keyword:{}", keyword);
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(result);
            Integer code = json.getInteger("code");
            if (code == null || code != 200) {
                log.error("searchSpuIdV3 error, result:{}", result);
                return null;
            }
            Object dataObj = json.get("data");
            if (dataObj == null) {
                return null;
            }
            JSONArray data;
            if (dataObj instanceof JSONArray) {
                data = (JSONArray) dataObj;
            } else {
                return null;
            }
            if (data.isEmpty()) {
                return null;
            }
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                if (keyword.equalsIgnoreCase(item.getString("articleNumber"))) {
                    return item.getLong("spuId");
                }
            }
            return data.getJSONObject(0).getLong("spuId");
        } catch (Exception e) {
            log.error("searchSpuIdV3 parse error, keyword:{}, msg:{}", keyword, e.getMessage());
            return null;
        }
    }

    private List<PoisonPriceDO> queryPriceV3(String modelNo, Long spuId) {
        LimiterHelper.limitPoisonPrice();
        String priceUrl = v3Url + PoisonApiConstant.V3_PRODUCT_PRICE + "?spu_id=" + spuId;
        String httpResult = HttpUtil.doGet(priceUrl, buildV3Headers(), false);
        if (httpResult == null) {
            log.error("queryPriceV3 error, no result, modelNo:{}, spuId:{}", modelNo, spuId);
            return Collections.emptyList();
        }
        try {
            JSONObject json = JSON.parseObject(httpResult);
            Integer code = json.getInteger("code");
            if (code == null || code != 200) {
                log.error("queryPriceV3 error, modelNo:{}, result:{}", modelNo, httpResult);
                return Collections.emptyList();
            }
            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                return Collections.emptyList();
            }
            JSONArray skus = data.getJSONArray("skus");
            if (skus == null || skus.isEmpty()) {
                return Collections.emptyList();
            }
            Date updateTime = data.getDate("update_time");
            List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
            for (int i = 0; i < skus.size(); i++) {
                JSONObject sku = skus.getJSONObject(i);
                Integer price = pickV3Price(sku);
                if (price == null || price <= 0 || price > 100 * PoisonSwitch.MAX_PRICE) {
                    continue;
                }
                PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                poisonPriceDO.setModelNo(modelNo);
                poisonPriceDO.setEuSize(extractSize(sku.getString("size")));
                poisonPriceDO.setPrice(price / 100);
                poisonPriceDO.setUpdateTime(updateTime);
                poisonPriceDOList.add(poisonPriceDO);
            }
            return poisonPriceDOList;
        } catch (Exception e) {
            log.error("queryPriceV3 parse error, modelNo:{}, spuId:{}, msg:{}", modelNo, spuId, e.getMessage());
            return Collections.emptyList();
        }
    }

    public JSONObject queryTokenInfo() {
        String infoUrl = v3Url + PoisonApiConstant.V3_TOKEN_INFO + "?token=" + tokenV3;
        String result = HttpUtil.doGet(infoUrl, buildV3Headers(), false);
        if (result == null) {
            log.error("queryTokenInfo error, no result");
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(result);
            Integer code = json.getInteger("code");
            if (code == null || code != 200) {
                log.error("queryTokenInfo error, result:{}", result);
                return null;
            }
            return json.getJSONObject("data");
        } catch (Exception e) {
            log.error("queryTokenInfo parse error, msg:{}", e.getMessage());
            return null;
        }
    }

    // ========== V4 API Methods ==========

    public List<PoisonPriceDO> queryPriceByModelNoV4(String modelNo) {
        if (ShoesContext.isNoPrice(modelNo)) {
            return Collections.emptyList();
        }
        LimiterHelper.limitPoisonPrice();
        String priceUrl = PoisonApiConstant.V4_PRICE + "?huohao=" + modelNo + "&code=" + v4Code;
        String httpResult = HttpUtil.doGet(priceUrl, false);
        if (httpResult == null) {
            log.error("queryPriceByModelNoV4 error, no result, modelNo:{}", modelNo);
            return Collections.emptyList();
        }
        try {
            JSONObject json = JSON.parseObject(httpResult);
            Integer code = json.getInteger("code");
            if (code == null || code != 200) {
                if (code != 901) {
                    log.error("queryPriceByModelNoV4 error, modelNo:{}, result:{}", modelNo, httpResult);
                }
                return Collections.emptyList();
            }
            JSONArray dataArray = json.getJSONArray("data");
            if (dataArray == null || dataArray.isEmpty()) {
                ShoesContext.addNoPrice(modelNo);
                return Collections.emptyList();
            }
            Date updateTime = json.getDate("update_time");
            List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                JSONObject item = dataArray.getJSONObject(i);
                Double price = item.getDouble("price");
                if (price == null || price <= 0 || price > PoisonSwitch.MAX_PRICE) {
                    continue;
                }
                PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                poisonPriceDO.setModelNo(modelNo);
                poisonPriceDO.setEuSize(ShoesUtil.normalizeUnicodeFraction(item.getString("size")));
                poisonPriceDO.setPrice(price.intValue());
                poisonPriceDO.setUpdateTime(updateTime);
                poisonPriceDOList.add(poisonPriceDO);
            }
            if (poisonPriceDOList.isEmpty()) {
                ShoesContext.addNoPrice(modelNo);
            }
            return poisonPriceDOList;
        } catch (Exception e) {
            log.error("queryPriceByModelNoV4 parse error, modelNo:{}, msg:{}", modelNo, e.getMessage());
            return Collections.emptyList();
        }
    }

    public void preloadSpuIds(Map<String, Long> modelNoToSpuId) {
        spuIdCache.putAll(modelNoToSpuId);
    }

    private Integer pickV3Price(JSONObject sku) {
        Integer normalPrice = sku.getInteger("normal_price");
        Integer storagePrice = sku.getInteger("storage_price");
        boolean hasNormal = normalPrice != null && normalPrice > 0;
        boolean hasStorage = storagePrice != null && storagePrice > 0;
        if (hasNormal && hasStorage) {
            return Math.min(normalPrice, storagePrice);
        } else if (hasNormal) {
            return normalPrice;
        } else if (hasStorage) {
            return storagePrice;
        }
        return null;
    }

    private Headers buildV3Headers() {
        return Headers.of("Authorization", tokenV3);
    }

    private void enhancePopParams(JSONObject params) {
        String accessToken = getValidPopAccessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            params.put("access_token", accessToken);
        } else {
            log.warn("POP access_token is empty. Open authorize URL and finish /poison/callback first.");
        }
        params.put("language", popLanguage);
        params.put("timeZone", popTimeZone);
        long timestamp = System.currentTimeMillis();
        String sign = SignUtil.sign(appKey, appSecret, timestamp, params);
        params.put("sign", sign);
    }

    public String buildPopAuthorizeUrl() {
        return "https://open.poizon.com/authorize?response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(popRedirectUri, StandardCharsets.UTF_8)
                + "&client_id=" + appKey
                + "&scope=all";
    }

    public synchronized JSONObject exchangePopAuthorizationCode(String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new IllegalArgumentException("authorizationCode is blank");
        }
        JSONObject params = new JSONObject();
        params.put("client_id", appKey);
        params.put("client_secret", appSecret);
        params.put("authorization_code", authorizationCode);
        String result = HttpUtil.doPost(POP_TOKEN_URL, params.toJSONString(), buildHeaders());
        JSONObject json = parsePopTokenResponse(result, "exchangePopAuthorizationCode");
        persistPopOAuthToken(json.getJSONObject("data"));
        return getPopOAuthStatus();
    }

    public synchronized String getValidPopAccessToken() {
        long now = System.currentTimeMillis();
        if (popAccessToken != null && !popAccessToken.isBlank()
                && popAccessTokenExpiresAt > now + TOKEN_REFRESH_BUFFER_MS) {
            return popAccessToken;
        }
        if (popRefreshToken == null || popRefreshToken.isBlank()) {
            return popAccessToken;
        }
        if (popRefreshTokenExpiresAt > 0 && popRefreshTokenExpiresAt <= now + TOKEN_REFRESH_BUFFER_MS) {
            log.warn("POP refresh_token is expired or about to expire. Need re-authorization.");
            return popAccessToken;
        }
        JSONObject params = new JSONObject();
        params.put("client_id", appKey);
        params.put("client_secret", appSecret);
        params.put("refresh_token", popRefreshToken);
        String result = HttpUtil.doPost(POP_REFRESH_TOKEN_URL, params.toJSONString(), buildHeaders());
        try {
            JSONObject json = parsePopTokenResponse(result, "refreshPopAccessToken");
            persistPopOAuthToken(json.getJSONObject("data"));
        } catch (Exception e) {
            log.error("refreshPopAccessToken error, msg:{}", e.getMessage());
        }
        return popAccessToken;
    }

    public JSONObject getPopOAuthStatus() {
        JSONObject status = new JSONObject();
        status.put("hasAccessToken", popAccessToken != null && !popAccessToken.isBlank());
        status.put("hasRefreshToken", popRefreshToken != null && !popRefreshToken.isBlank());
        status.put("openId", popOpenId);
        status.put("accessTokenExpiresAt", popAccessTokenExpiresAt);
        status.put("refreshTokenExpiresAt", popRefreshTokenExpiresAt);
        status.put("authorizeUrl", buildPopAuthorizeUrl());
        return status;
    }

    private JSONObject parsePopTokenResponse(String result, String operation) {
        if (result == null) {
            throw new IllegalStateException(operation + " no response");
        }
        JSONObject json = JSON.parseObject(result);
        Integer code = json.getInteger("code");
        JSONObject data = json.getJSONObject("data");
        if (code == null || code != 200 || data == null) {
            throw new IllegalStateException(operation + " failed: " + result);
        }
        return json;
    }

    private void persistPopOAuthToken(JSONObject data) {
        long now = System.currentTimeMillis();
        popAccessToken = data.getString("access_token");
        popRefreshToken = data.getString("refresh_token");
        popOpenId = data.getString("open_id");
        Long accessExpiresIn = data.getLong("access_token_expires_in");
        Long refreshExpiresIn = data.getLong("refresh_token_expires_in");
        popAccessTokenExpiresAt = accessExpiresIn == null ? 0L : now + accessExpiresIn * 1000L;
        popRefreshTokenExpiresAt = refreshExpiresIn == null ? 0L : now + refreshExpiresIn * 1000L;
        log.info("POP OAuth token persisted, openId:{}, accessTokenExpiresIn:{}s expiresAt:{}, refreshTokenExpiresIn:{}s expiresAt:{}",
                popOpenId, accessExpiresIn, new Date(popAccessTokenExpiresAt),
                refreshExpiresIn, new Date(popRefreshTokenExpiresAt));
        if (accessExpiresIn == null || refreshExpiresIn == null) {
            log.warn("POP token expires_in field missing (accessExpiresIn:{}, refreshExpiresIn:{}), "
                    + "check field names against POP doc, otherwise every query will trigger a refresh",
                    accessExpiresIn, refreshExpiresIn);
        }

        Properties properties = new Properties();
        properties.setProperty("access.token", Objects.toString(popAccessToken, ""));
        properties.setProperty("refresh.token", Objects.toString(popRefreshToken, ""));
        properties.setProperty("open.id", Objects.toString(popOpenId, ""));
        properties.setProperty("access.token.expires.at", String.valueOf(popAccessTokenExpiresAt));
        properties.setProperty("refresh.token.expires.at", String.valueOf(popRefreshTokenExpiresAt));
        configService.saveConfig(POP_OAUTH_CONFIG_FILE, properties);
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 根据商品货号查询商品信息，主要为了拿到spuId用于查价
     * @param modelNumberList 商品货号
     * @return 商品详细信息
     */
    public List<PoisonItemDO> queryItemByModelNos(List<String> modelNumberList) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("article_numbers", modelNumberList);
        enhanceParams(params);
        String result = HttpUtil.doPost(url + PoisonApiConstant.BATCH_ARTICLE_NUMBER, JSON.toJSONString(params), buildHeaders());
        Result<List<PoisonItemDO>> parseRes = JSON.parseObject(result, new TypeReference<>() {});
        if (parseRes == null || CollectionUtils.isEmpty(parseRes.getData())) {
            log.error("queryItemByModelNos error, msg:{}", result);
            return Collections.emptyList();
        }
        return parseRes.getData();
    }

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json"
        );
    }

    private void enhanceParams(Map<String, Object> params) {
        long timestamp = System.currentTimeMillis();
        String sign = SignUtil.sign(appKey, appSecret, timestamp, params);
        params.put("sign", sign);
        params.put("timestamp", timestamp);
        params.put("app_key", appKey);
    }

    /**
     * 从size字符串中提取尺码数字
     * 例如："灰色 35.5" -> "35.5"，"35.5" -> "35.5"
     */
    private String extractSize(String size) {
        if (size == null || size.isEmpty()) {
            return size;
        }
        size = ShoesUtil.normalizeUnicodeFraction(size);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\d+(\\.\\d+|\\s*\\d*/\\d*)?");
        java.util.regex.Matcher matcher = pattern.matcher(size);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return size;
    }

    @Data
    public static class Result<T> {

        private Integer code;

        private String msg;

        private T data;
    }
}
