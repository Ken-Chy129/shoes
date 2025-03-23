package cn.ken.shoes.client;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.common.PoisonApiConstant;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.config.PoisonConfig;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.model.entity.KickScrewPriceDO;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.poinson.PoisonItemPrice;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SignUtil;
import com.alibaba.fastjson.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Slf4j
@Component
public class PoisonClient {

    private static final Logger lackModelLogger = Logger.getLogger("lackModelLogger");

    @Value("${poison.token}")
    private String token;


    public List<PoisonPriceDO> queryPriceV3(Long spuId) {
        String url = PoisonApiConstant.PRICE_BY_SPU_V3
                .replace("{spuId}", String.valueOf(spuId));
        String rawResult = HttpUtil.doGet(url, Headers.of(
                "Authorization", token
        ));
        JSONObject json;
        if (StrUtil.isBlank(rawResult) || (json = JSONObject.parseObject(rawResult)) == null || json.getInteger("code") != 200) {
            return Collections.emptyList();
        }
        List<PoisonPriceDO> result = new ArrayList<>();
        JSONObject data = json.getJSONObject("data");
        String modelNo = data.getString("article_number");
        for (JSONObject jsonObject : data.getJSONArray("skus").toJavaList(JSONObject.class)) {
            PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
            poisonPriceDO.setModelNo(modelNo);
            Integer brandPrice = jsonObject.getInteger("brand_supply_price");
            Integer fastPrice = jsonObject.getInteger("fast_price");
            Integer normalPrice = jsonObject.getInteger("normal_price");
            Integer storagePrice = jsonObject.getInteger("storage_price");
            Integer minPrice = Stream.of(brandPrice, fastPrice, normalPrice, storagePrice).filter(price -> price != 0).sorted().findFirst().orElse(null);
            if (minPrice == null || minPrice > 100 * PoisonSwitch.MAX_PRICE) {
                continue;
            }
            poisonPriceDO.setPrice(minPrice / 100);
            poisonPriceDO.setEuSize(ShoesUtil.getEuSizeFromPoison(jsonObject.getString("size")));
            result.add(poisonPriceDO);
        }
        return result;
    }

    public List<PoisonPriceDO> queryPriceBySpuV2(String modelNo, Long spuId) {
        String url = PoisonApiConstant.PRICE_BY_SPU_V2
                .replace("{spuId}", String.valueOf(spuId))
                .replace("{token}", token);
        String result = HttpUtil.doGet(url);
        try {
            if ("{}".equals(result)) {
                lackModelLogger.info(modelNo);
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
     * 根据spu查询价格
     * @param spuId 商品spuId，通过货号唯一对应一个spuId
     * @return 商品价格，包括闪电价格，普通价格和极速价格
     */
    public List<PoisonPriceDO> queryPriceBySpu(String modelNo, Long spuId) {
        String url = PoisonApiConstant.PRICE_BY_SPU;
        Map<String, String> params = new HashMap<>();
        params.put("spuId", String.valueOf(spuId));
        params.put("token", PoisonConfig.TOKEN);
        String result = HttpUtil.doPost(url, JSON.toJSONString(params));
        Result<JSONObject> parseRes = JSON.parseObject(result, new TypeReference<>() {});
        if (parseRes == null || parseRes.getData() == null || parseRes.getCode() == null) {
            log.error("JSON解析结果为空, spuId:{}, result:{}", spuId, result);
            return null;
        }
        if (parseRes.getCode() != 200) {
            log.error("查询的得物价格失败, spuId:{}, msg:{}", spuId, parseRes.getMsg());
            if (parseRes.getCode() == 205) {
                log.error("余额不足, msg:{}", parseRes.getMsg());
                throw new RuntimeException("余额不足");
            }
            return null;
        }
        List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
        Optional.ofNullable(parseRes.getData())
                .map(json -> json.getJSONArray("price_detail"))
                .map(jsonArray -> jsonArray.toJavaList(JSONObject.class))
                .orElse(Collections.emptyList())
                .forEach(json -> {
                    String size = json.getString("size").replace("⅓", ".33").replace("⅔", ".67");
                    PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                    poisonPriceDO.setEuSize(size);
                    poisonPriceDO.setModelNo(modelNo);
                    JSONObject price = json.getJSONObject("price");
                    for (String type : price.keySet()) {
                        PriceEnum priceEnum = PriceEnum.from(type);
                        if (priceEnum == null) {
                            log.info("未知的价格类型, type:{}, spuId:{}", type, spuId);
                            continue;
                        }
                        if (poisonPriceDO.getPrice() == null) {
                            poisonPriceDO.setPrice(price.getInteger(type) / 100);
                        } else {
                            poisonPriceDO.setPrice(Math.min(poisonPriceDO.getPrice(), price.getInteger(type) / 100));
                        }
                    }
                    if (poisonPriceDO.getPrice() != null && poisonPriceDO.getPrice() <= PoisonSwitch.MAX_PRICE) {
                        poisonPriceDOList.add(poisonPriceDO);
                    }
                });
        return poisonPriceDOList;
    }

    /**
     * 查询token余额
     * @return token余额
     */
    public String queryTokenBalance() {
        String url = PoisonApiConstant.TOKEN_BALANCE;
        Map<String, String> params = new HashMap<>();
        params.put("token", PoisonConfig.TOKEN);
        return HttpUtil.doPost(url, JSON.toJSONString(params));
    }

    /**
     * 根据商品货号查询商品信息，主要为了拿到spuId用于查价
     * @param modelNumberList 商品货号
     * @return 商品详细信息
     */
    public List<PoisonItemDO> queryItemByModelNos(List<String> modelNumberList) {
        String url = PoisonConfig.getUrlPrefix() + PoisonApiConstant.BATCH_ARTICLE_NUMBER;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("article_numbers", modelNumberList);
        enhanceParams(params);
        String result = HttpUtil.doPost(url, JSON.toJSONString(params), buildHeaders());
        Result<List<PoisonItemDO>> parseRes = JSON.parseObject(result, new TypeReference<>() {});
        if (parseRes == null || CollectionUtils.isEmpty(parseRes.getData())) {
            log.error("queryItemByModelNos error, msg:{}", result);
            return Collections.emptyList();
        }
        return parseRes.getData();
    }

    /**
     * 根据sku
     * @param skuId sku，商品的每个尺码对应不同的skuId
     * @param priceEnum 查询得物价格的类型，目前对应自营卖家接入，只有查询普通发货价格的权限
     * @return 商品指定尺码指定发货类型的最低价格
     */
    public Integer queryLowestPriceBySkuId(Long skuId, PriceEnum priceEnum) {
        String url = PoisonConfig.getUrlPrefix() + getPriceApi(priceEnum);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sku_id", skuId);
        enhanceParams(params);
        String result = HttpUtil.doGet(url, params);
        Result<List<PoisonItemPrice>> parseRes = JSON.parseObject(result, new TypeReference<>() {});
        if (parseRes == null || CollectionUtils.isEmpty(parseRes.getData())) {
            return null;
        }
        PoisonItemPrice first = parseRes.getData().getFirst();
        return first.getItems().getFirst().getLowestPrice();
    }

    private String getPriceApi(PriceEnum priceEnum) {
        String priceApi;
        switch (priceEnum) {
            case LIGHTNING -> priceApi = PoisonApiConstant.LOWEST_PRICE;
//            case FAST -> priceApi = PoisonApiConstant.FAST_LOWEST_PRICE;
            default -> priceApi = PoisonApiConstant.NORMAL_LOWEST_PRICE;
        }
        return priceApi;
    }

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json"
        );
    }

    private void enhanceParams(Map<String, Object> params) {
        long timestamp = System.currentTimeMillis();
        String sign = SignUtil.sign(PoisonConfig.getAppKey(), PoisonConfig.getAppSecret(), timestamp, params);
        params.put("sign", sign);
        params.put("timestamp", timestamp);
        params.put("app_key", PoisonConfig.getAppKey());
    }

    @Data
    public static class Result<T> {

        private Integer code;

        private String msg;

        private T data;
    }
}
