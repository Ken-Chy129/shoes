package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayApplicationTokenService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class EbayNotificationPublicKeyClient {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final EbayApplicationTokenService tokenService;
    private final OkHttpClient httpClient;
    private final HttpUrl notificationBaseUrl;

    @Autowired
    public EbayNotificationPublicKeyClient(EbayProperties properties,
                                           EbayApplicationTokenService tokenService) {
        this(properties, tokenService,
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getNotificationApiEndpoint());
    }

    EbayNotificationPublicKeyClient(EbayProperties properties,
                                    EbayApplicationTokenService tokenService,
                                    OkHttpClient httpClient,
                                    String notificationBaseUrl) {
        this.tokenService = tokenService;
        this.httpClient = httpClient;
        this.notificationBaseUrl = requireHttpUrl(notificationBaseUrl);
    }

    public PublicKeyData getPublicKey(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("invalid eBay notification public key id");
        }
        HttpUrl url = notificationBaseUrl.newBuilder()
                .addPathSegment("public_key")
                .addPathSegment(keyId)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokenService.getValidAccessToken())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseText = responseText(response.body());
            if (response.code() != 200) {
                throw new EbayApiException(
                        "eBay notification public key request failed (HTTP "
                                + response.code() + ")");
            }
            JSONObject json = JSON.parseObject(responseText);
            String key = json == null ? null : json.getString("key");
            String algorithm = json == null ? null : json.getString("algorithm");
            String digest = json == null ? null : json.getString("digest");
            if (key == null || key.isBlank() || algorithm == null || algorithm.isBlank()
                    || digest == null || digest.isBlank()) {
                throw new EbayApiException(
                        "eBay notification public key response is incomplete");
            }
            return new PublicKeyData(key, algorithm, digest);
        } catch (IOException e) {
            throw new EbayApiException(
                    "eBay notification public key request failed due to a network error", e);
        }
    }

    private String responseText(ResponseBody body) throws IOException {
        return body == null ? "" : body.string();
    }

    private HttpUrl requireHttpUrl(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !("https".equals(parsed.scheme()) || isLoopbackHttp(parsed))) {
            throw new IllegalArgumentException("eBay notification API endpoint must use HTTPS");
        }
        return parsed;
    }

    private boolean isLoopbackHttp(HttpUrl url) {
        return "http".equals(url.scheme())
                && ("localhost".equals(url.host()) || "127.0.0.1".equals(url.host()));
    }

    public record PublicKeyData(String key, String algorithm, String digest) {
    }
}
