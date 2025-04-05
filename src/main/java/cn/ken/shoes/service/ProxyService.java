package cn.ken.shoes.service;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProxyService {

    @Value("${proxy.ip}")
    private String proxyIp;

    @Value("${proxy.port}")
    private int proxyPort;

    @Value("${proxy.secret}")
    private String proxySecret;

    public Result<JSONObject> getAllProxies() {
        String result = HttpUtil.doGet(STR."https://\{proxyIp}:\{proxyPort}/proxies", getHeaders());
        return Result.buildSuccess(JSON.parseObject(result));
    }

    public Result<JSONObject> changeProxy(String name) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("name", name);
        String result = HttpUtil.doPut(STR."https://\{proxyIp}:\{proxyPort}/proxies/蓝胖云LanPangYun", jsonObject.toJSONString(), getHeaders());
        return Result.buildSuccess(JSON.parseObject(result));
    }

    private Headers getHeaders() {
        return Headers.of("Authorization", STR."Bearer \{proxySecret}");
    }
}
