package cn.ken.shoes.util;

import cn.ken.shoes.config.ProxyConfig;
import com.alibaba.fastjson.JSON;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpUtil {

    private static final String STOCKX_DEVICE_ID = UUID.randomUUID().toString();

    /**
     * 代理请求限流器，每秒5次
     */
    private static final RateLimiter PROXY_LIMITER = RateLimiter.create(5);

    private static volatile OkHttpClient proxyClient;

    private static final OkHttpClient client = new OkHttpClient()
            .newBuilder()
            .build();

    private static OkHttpClient getProxyClient() {
        if (proxyClient == null) {
            synchronized (HttpUtil.class) {
                if (proxyClient == null) {
                    ProxyConfig config = ProxyConfig.getInstance();
                    proxyClient = new OkHttpClient()
                            .newBuilder()
                            .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(config.getHost(), config.getPort())))
                            .proxyAuthenticator((route, response) -> {
                                String credential = Credentials.basic(config.getUsername(), config.getPassword());
                                return response.request().newBuilder().header("Proxy-Authorization", credential).build();
                            })
                            .build();
                }
            }
        }
        return proxyClient;
    }

    private static OkHttpClient getClient(boolean useProxy) {
        return useProxy ? getProxyClient() : client;
    }

    private static final int MAX_RETRIES = 2;

    private static String executeWithRetry(Request request, boolean useProxy, String method) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            ResponseBody responseBody = null;
            try (Response response = getClient(useProxy).newCall(request).execute()) {
                responseBody = response.body();
                if (responseBody != null) {
                    String bodyStr = responseBody.string();
                    if (!response.isSuccessful()) {
                        log.warn("{} 响应异常, url:{}, httpCode:{}, bodyLen:{}, contentType:{}, headers:{}",
                                method, request.url(), response.code(), bodyStr.length(),
                                response.header("Content-Type"), response.headers());
                        if (response.code() == 403 || response.code() == 429) {
                            if (attempt < MAX_RETRIES) {
                                log.warn("{} {}被拦截, 第{}次重试", method, response.code(), attempt + 1);
                                continue;
                            }
                        }
                    }
                    return bodyStr;
                }
            } catch (SocketTimeoutException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("{} timeout, url:{}, 第{}次重试", method, request.url(), attempt + 1);
                } else {
                    log.error("{} error, url:{}, error:timeout(已重试{}次)", method, request.url(), MAX_RETRIES);
                }
            } catch (IOException e) {
                log.error("{} error, url:{}, responseBody:{}, error:{}", method, request.url(), JSON.toJSONString(responseBody), e.getMessage());
                return null;
            }
        }
        return null;
    }

    public static String doGet(HttpUrl url) {
        Request request = new Request.Builder().url(url).build();
        ResponseBody body = null;
        try (Response response = client.newCall(request).execute()) {
            body = response.body();
            if (body != null) {
                return body.string();
            }
        } catch (IOException e) {
            log.error("doGet error, url:{}, responseBody:{}, error:{}", url, JSON.toJSONString(body), e.getMessage());
        }
        return null;
    }

    public static String doGet(String url, boolean useProxy) {
        return doGet(url, Headers.of(), useProxy);
    }

    public static String doGet(String url) {
        return doGet(url, Headers.of(), true);
    }

    public static String doGet(String url, Headers headers) {
        return doGet(url, headers, true);
    }

    public static String doGet(String url, Headers headers, boolean useProxy) {
        if (useProxy) {
            PROXY_LIMITER.acquire();
        }
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        return executeWithRetry(request, useProxy, "doGet");
    }

    public static void downloadFile(String url, Headers headers, String path) {
        PROXY_LIMITER.acquire();
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        ResponseBody responseBody = null;
        try (Response response = getClient(true).newCall(request).execute()) {
            responseBody  = response.body();
            if (responseBody != null) {
                File file = new File(path);
                try (FileOutputStream outputStream = new FileOutputStream(file)) {
                    outputStream.write(responseBody.bytes());
                }
            }
        } catch (IOException e) {
            log.error("downloadFile error, url:{}, responseBody:{}, error:{}", url, JSON.toJSONString(responseBody), e.getMessage());
        }
    }

    public static String doPost(String url, String json) {
        return doPost(url, json, Headers.of());
    }

    public static String doPost(String url, String json, Headers headers) {
        PROXY_LIMITER.acquire();
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .post(body)
                .build();
        return executeWithRetry(request, true, "doPost");
    }

    public static String doPut(String url, String json, Headers headers) {
        PROXY_LIMITER.acquire();
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .put(body)
                .build();
        return executeWithRetry(request, true, "doPut");
    }

    public static String doDelete(String url, String json, Headers headers) {
        PROXY_LIMITER.acquire();
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .delete(body)
                .build();
        return executeWithRetry(request, true, "doDelete");
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
            log.error("buildUrlParams error, params:{}, error:{}", JSON.toJSONString(params), e.getMessage());
        }
        return queryStringBuilder.toString();
    }

    public static String getStockXDeviceId() {
        return STOCKX_DEVICE_ID;
    }
}
