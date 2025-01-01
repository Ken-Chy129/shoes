package cn.ken.shoes.client;

import cn.ken.shoes.common.PoiSonApiConstant;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.PoisonConfig;
import cn.ken.shoes.model.poinson.ItemPrice;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.SignUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PoisonClient {

    public PoisonItem queryItemByModelNumber(String modelNumber) {
        String url = PoisonConfig.getUrlPrefix() + PoiSonApiConstant.BATCH_ARTICLE_NUMBER;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("article_numbers", Collections.singletonList(modelNumber));
        enhanceParams(params);
        log.info("queryItemByModelNumber-request:{}", params);
        String result = HttpUtil.doPost(url, JSON.toJSONString(params), buildHeaders());
        log.info("queryItemByModelNumber-response:{}", result);
        Result<List<PoisonItem>> parseRes = JSON.parseObject(result, new TypeReference<>() {});
        if (parseRes == null || CollectionUtils.isEmpty(parseRes.getData())) {
            return null;
        }
        return parseRes.getData().getFirst();
    }

    public Integer queryLowestPriceBySkuId(Long skuId, PriceEnum priceEnum) {
        String url = PoisonConfig.getUrlPrefix() + getPriceApi(priceEnum);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sku_id", skuId);
        enhanceParams(params);
        String result = HttpUtil.doGet(url, params);
        Result<List<ItemPrice>> parseRes = JSON.parseObject(result, new TypeReference<>() {});
        if (parseRes == null || CollectionUtils.isEmpty(parseRes.getData())) {
            return null;
        }
        ItemPrice first = parseRes.getData().getFirst();
        return first.getItems().getFirst().getLowestPrice();
    }

    private String getPriceApi(PriceEnum priceEnum) {
        String priceApi;
        switch (priceEnum) {
            case LIGHTNING -> priceApi = PoiSonApiConstant.LOWEST_PRICE;
            case FAST -> priceApi = PoiSonApiConstant.FAST_LOWEST_PRICE;
            default -> priceApi = PoiSonApiConstant.NORMAL_LOWEST_PRICE;
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
}
