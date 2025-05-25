package cn.ken.shoes.util;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class HttpUtil {

    private static final Random RANDOM = new Random();

//    private static final OkHttpClient proxyClient = new OkHttpClient()
//            .newBuilder()
//            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("hostname", 20818)))
//            .proxyAuthenticator((route, response) -> {
//                String credential = Credentials.basic("username", "password");
//                return response.request().newBuilder().header("Proxy-Authorization", credential).build();
//            })
//            .build();

    private static final OkHttpClient proxyClient = new OkHttpClient()
            .newBuilder()
            .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", 7890)))
            .build();

    private static final OkHttpClient client = new OkHttpClient()
            .newBuilder()
            .build();

    private static OkHttpClient getClient(boolean useProxy) {
        return useProxy ? proxyClient : client;
    }

    public static String doGet(String url, Map<String, Object> params) {
        String queryString = SignUtil.mapToQueryString(params);
        if (StrUtil.isNotBlank(queryString)) {
            url += "?" + queryString;
        }
        return doGet(url, Headers.of(), true);
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
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        ResponseBody responseBody = null;
        try (Response response = getClient(useProxy).newCall(request).execute()) {
            responseBody  = response.body();
            if (responseBody != null) {
                return responseBody.string();
            }
        } catch (IOException e) {
            log.error("doGet error, url:{}, responseBody:{}, error:{}", url, JSON.toJSONString(responseBody), e.getMessage());
        }
        return null;
    }

    public static void downloadFile(String url, Headers headers, String path) {
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
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .post(body)
                .build();
        ResponseBody responseBody = null;
        try (Response response = getClient(true).newCall(request).execute()) {
            responseBody  = response.body();
            if (responseBody != null) {
                return responseBody.string();
            }
        } catch (IOException e) {
            log.error("doPost error, url:{}, responseBody:{}, error:{}", url, JSON.toJSONString(responseBody), e.getMessage());
        }
        return null;
    }

    public static String doPut(String url, String json, Headers headers) {
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .put(body)
                .build();
        ResponseBody responseBody = null;
        try (Response response = getClient(true).newCall(request).execute()) {
            responseBody  = response.body();
            if (responseBody != null) {
                return responseBody.string();
            }
        } catch (IOException e) {
            log.error("doPut error, url:{}, responseBody:{}, error:{}", url, JSON.toJSONString(responseBody), e.getMessage());
        }
        return null;
    }

    public static String doDelete(String url, String json, Headers headers) {
        RequestBody body = RequestBody.create(MediaType.get("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .delete(body)
                .build();
        ResponseBody responseBody = null;
        try (Response response = getClient(true).newCall(request).execute()) {
            responseBody  = response.body();
            if (responseBody != null) {
                return responseBody.string();
            }
        } catch (IOException e) {
            log.error("doDelete error, url:{}, responseBody:{}, error:{}", url, JSON.toJSONString(responseBody), e.getMessage());
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
            log.error("buildUrlParams error, params:{}, error:{}", JSON.toJSONString(params), e.getMessage());
        }
        return queryStringBuilder.toString();
    }

    public static String getRandomUserAgent() {
        List<String> userAgentList = List.of(
                "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:34.0) Gecko/20100101 Firefox/34.0",
                "Mozilla/5.0 (Windows NT 5.1; U; en; rv:1.8.1) Gecko/20061208 Firefox/2.0.0 Opera 9.50",
                "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; en) Opera 9.50",
                "Mozilla/5.0 (Windows NT 6.1; rv,2.0.1) Gecko/20100101 Firefox/4.0.1",
                "Opera/9.80 (Windows NT 6.1; U; en) Presto/2.8.131 Version/11.11",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.6; rv,2.0.1) Gecko/20100101 Firefox/4.0.1",
                "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/534.57.2 (KHTML, like Gecko) Version/5.1.7 Safari/534.57.2",
                "Windows：Mozilla/5.0 (Windows; U; Windows NT 6.1; en-us) AppleWebKit/534.50 (KHTML, like Gecko) Version/5.1 Safari/534.50",
                "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.64 Safari/537.11",
                "Mozilla/5.0 (Windows; U; Windows NT 6.1; en-US) AppleWebKit/534.16 (KHTML, like Gecko) Chrome/10.0.648.133 Safari/534.16",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_7_0) AppleWebKit/535.11 (KHTML, like Gecko) Chrome/17.0.963.56 Safari/535.11",
                "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/30.0.1599.101 Safari/537.36",
                "Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; rv:11.0) like Gecko",
                "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1; 360SE)",
                "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.122 UBrowser/4.0.3214.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.87 UBrowser/6.2.4094.1 Safari/537.36",
                "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0;",
                "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.0)",
                "Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 6.0; Trident/4.0)",
                "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)",
                "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1)",
                "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1; The World)",
                "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1; TencentTraveler 4.0)",
                "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1; Avant Browser)"
        );
        return userAgentList.get(RANDOM.nextInt(25));
    }
}
