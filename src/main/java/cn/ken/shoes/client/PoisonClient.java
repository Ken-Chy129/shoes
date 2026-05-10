package cn.ken.shoes.client;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.KickScrewApiConstant;
import cn.ken.shoes.common.PoisonApiConstant;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SignUtil;
import com.alibaba.fastjson.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PoisonClient {

    @Value("${poison.url}")
    private String url;

    @Value("${poison.appKey}")
    private String appKey;

    @Value("${poison.appSecret}")
    private String appSecret;

    @Value("${poison.token}")
    private String token;

    @Value("${poison.tokenV2}")
    private String tokenV2;

    @Value("${poison.v3Url}")
    private String v3Url;

    @Value("${poison.tokenV3}")
    private String tokenV3;

    @Value("${poison.v4Code}")
    private String v4Code;

    private final ConcurrentHashMap<String, Long> spuIdCache = new ConcurrentHashMap<>();

    public List<PoisonPriceDO> batchQueryPrice(List<String> modelNos) {
        if (CollectionUtils.isEmpty(modelNos)) {
            return Collections.emptyList();
        }
        if (PoisonSwitch.USE_V4_API || PoisonSwitch.USE_V3_API) {
            return batchQueryPriceOneByOne(modelNos);
        }
        // 重试
        int times = 0;
        String result;
        do {
            LimiterHelper.limitPoisonPrice();
            JSONObject params = new JSONObject();
            params.put("artno_list", modelNos);
            params.put("token", token);
            result = HttpUtil.doPost(PoisonApiConstant.BATCH_PRICE, params.toJSONString());
            times++;
        } while (result == null && times <= 3);
        if (result == null) {
            log.error("batchQueryPrice error, no result:{}", modelNos);
            return Collections.emptyList();
        }
        JSONObject jsonObject = JSON.parseObject(result);
        Integer code = jsonObject.getInteger("code");
        if (code != 200) {
            log.error("batchQueryPrice error, result: {}", result);
            return Collections.emptyList();
        }
        List<JSONObject> skuList = jsonObject.getJSONArray("sku_list").toJavaList(JSONObject.class);
        List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
        for (JSONObject sku : skuList) {
            Date time = sku.getDate("update_time");
            LocalDate update = LocalDate.ofInstant(time.toInstant(), ZoneId.systemDefault());
            LocalDate threeDayAgo = LocalDate.now().minusDays(3);
            if (update.isBefore(threeDayAgo)) {
                continue;
            }
            String modelNo = sku.getString("article_number");
            List<JSONObject> priceList = sku.getJSONArray("data").toJavaList(JSONObject.class);
            for (JSONObject priceData : priceList) {
                Integer minprice = priceData.getInteger("minprice");
                if (minprice == null || minprice <= 0 || minprice > 100 * PoisonSwitch.MAX_PRICE) {
                    continue;
                }
                PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                poisonPriceDO.setModelNo(modelNo);
                poisonPriceDO.setEuSize(extractSize(priceData.getString("size")));
                poisonPriceDO.setPrice(minprice / 100);
                poisonPriceDO.setUpdateTime(time);
                poisonPriceDOList.add(poisonPriceDO);
            }
        }
        return poisonPriceDOList;
    }

    private List<PoisonPriceDO> batchQueryPriceOneByOne(List<String> modelNos) {
        List<PoisonPriceDO> allPrices = new ArrayList<>();
        for (String modelNo : modelNos) {
            List<PoisonPriceDO> prices = queryPriceByModelNo(modelNo);
            allPrices.addAll(prices);
        }
        return allPrices;
    }

    public List<PoisonPriceDO> queryPriceByModelNo(String modelNo) {
        if (modelNo == null) {
            return Collections.emptyList();
        }
        if (PoisonSwitch.USE_V4_API) {
            return queryPriceByModelNoV4(modelNo);
        } else if (PoisonSwitch.USE_V3_API) {
            return queryPriceByModelNoV3(modelNo);
        } else if (PoisonSwitch.USE_V2_API) {
            return queryPriceByModelNoV2(modelNo);
        } else {
            return queryPriceByModelNoV1(modelNo);
        }
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
            poisonPriceDO.setEuSize(euSize);
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
                poisonPriceDO.setEuSize(item.getString("size"));
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
        // 匹配尺码数字，支持整数和小数，如 35、35.5、36 1/3
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
