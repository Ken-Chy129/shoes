package cn.ken.shoes.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;

import java.util.List;

@Slf4j
public class ProxyUtil {

    private final static String IP = "127.0.0.1";

    private final static int PORT = 60111;

    private final static String SECRET = "e56221e6-4cfd-4614-b9ce-2a047db37e0b";

    private final static String SELECTOR_NAME = "蓝胖云LanPangYun";

    private final static List<String> PROXY_NODE_LIST;

    private static Integer INDEX = 0;

    static {
        List<String> blackNodeList = List.of("自动选择", "故障转移", "最新官网", "剩余流量", "距离下次", "套餐到期");
        String result = HttpUtil.doGet(STR."http://\{IP}:\{PORT}/proxies", getHeaders());
        JSONObject jsonObject = JSON.parseObject(result);
        JSONObject proxies = jsonObject.getJSONObject("proxies");
        List<String> allProxies = proxies.getJSONObject(SELECTOR_NAME).getJSONArray("all").toJavaList(String.class);
        PROXY_NODE_LIST = allProxies.stream().filter(proxyName -> {
            for (String blackName : blackNodeList) {
                if (proxyName.startsWith(blackName)) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    public synchronized static void changeNode() {
        if (INDEX == PROXY_NODE_LIST.size()) {
            INDEX = 0;
        }
        String nextNodeName = PROXY_NODE_LIST.get(INDEX++);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("name", nextNodeName);
        HttpUtil.doPut(STR."http://\{IP}:\{PORT}/proxies/\{SELECTOR_NAME}", jsonObject.toJSONString(), getHeaders());
    }

    private static Headers getHeaders() {
        return Headers.of("Authorization", STR."Bearer \{SECRET}");
    }
}
