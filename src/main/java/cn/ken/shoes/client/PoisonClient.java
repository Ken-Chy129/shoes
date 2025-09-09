package cn.ken.shoes.client;

import cn.ken.shoes.ShoesContext;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

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

    public List<PoisonPriceDO> queryPriceByModelNo(String modelNo) {
        if (modelNo == null) {
            return Collections.emptyList();
        }
        if (PoisonSwitch.USE_V2_API) {
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
            result = doQueryPriceByModelNoV2(modelNo);
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
            result = doQueryPriceByModelNo(modelNo);
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
            String size = ShoesUtil.getEuSizeFromPoison(data.getString("size"));
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

    private String doQueryPriceByModelNo(String modelNo) {
        LimiterHelper.limitPoisonPrice();
        String url = PoisonApiConstant.PRICE_BY_MODEL_NO
                .replace("{modelNo}", modelNo)
                .replace("{token}", token);
        return HttpUtil.doGet(url, false);
    }

    private String doQueryPriceByModelNoV2(String modelNo) {
        LimiterHelper.limitPoisonPrice();
        String url = PoisonApiConstant.PRICE_BY_MODEL_NO_V2.replace("{modelNo}", modelNo);
        return HttpUtil.doGet(url, false);
    }

    public List<PoisonPriceDO> queryPriceBySpuV2(String modelNo, Long spuId) {
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
                String size = ShoesUtil.getEuSizeFromPoison(data.getString("size"));
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

    @Data
    public static class Result<T> {

        private Integer code;

        private String msg;

        private T data;
    }
}
