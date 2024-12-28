package cn.ken.shoes.util;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;

//@Slf4j
public class HttpUtil {

    private static final Logger log = LoggerFactory. getLogger(HttpUtil.class);

    private static final OkHttpClient client = new OkHttpClient()
            .newBuilder()
            .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", 7890)))
            .build();

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

}
