package cn.ken.shoes.service;

import cn.ken.shoes.common.PoiSonApiConstant;
import cn.ken.shoes.config.PoisonConfig;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.SignUtil;
import com.alibaba.fastjson.JSON;
import okhttp3.Headers;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PoisonService {

    public String queryItemByModelNumber(String modelNumber) {
        String url = PoisonConfig.getUrlPrefix() + PoiSonApiConstant.BATCH_ARTICLE_NUMBER;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("article_numbers", Collections.singletonList(modelNumber));
        return HttpUtil.doPost(url, enhanceParams(params), buildHeaders());
    }

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json"
        );
    }

    private String enhanceParams(Map<String, Object> params) {
        long timestamp = System.currentTimeMillis();
        String sign = SignUtil.sign(PoisonConfig.getAppKey(), PoisonConfig.getAppSecret(), timestamp, params);
        params.put("sign", sign);
        params.put("timestamp", timestamp);
        params.put("app_key", PoisonConfig.getAppKey());
        return JSON.toJSONString(params);
    }

}
