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
            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", 7890)))
            .build();

    public static String doGet(String url, Map<String, Object> params) {
        String queryString = SignUtil.mapToQueryString(params);
        if (StrUtil.isNotBlank(queryString)) {
            url += "?" + queryString;
        }
        return doGet(url);
    }

    public static String doGet(String url) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            System.out.println(response);
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
