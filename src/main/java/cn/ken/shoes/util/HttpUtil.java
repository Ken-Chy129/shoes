package cn.ken.shoes.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

//@Slf4j
public class HttpUtil {

    private static final Logger log = LoggerFactory. getLogger(HttpUtil.class);

    private static final OkHttpClient client = new OkHttpClient()
            .newBuilder()
            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("222.219.133.164", 16790)))
            .proxyAuthenticator((route, response) -> {
                String credential = Credentials.basic("90113", "34674");
                return response.request().newBuilder().header("Proxy-Authorization", credential).build();
            })
            .build();

    public static String doGet(String url, Map<String, Object> params) {
        String queryString = SignUtil.mapToQueryString(params);
        if (StrUtil.isNotBlank(queryString)) {
            url += "?" + queryString;
        }
        log.info("url:{}", url);
        return doGet(url);
    }

    public static String doGet(String url) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return null;
    }

    public static String doPost(String url, String json) {
        return doPost(url, json, null);
    }

    public static String doPost(String url, String json, Headers headers) {
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return null;
    }

    public static String buildUrlParams(Map<String, Object> params) {
        StringBuilder queryStringBuilder = new StringBuilder();
        try {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (!queryStringBuilder.isEmpty()) {
                    queryStringBuilder.append("&");
                }
                queryStringBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return queryStringBuilder.toString();
    }
}
