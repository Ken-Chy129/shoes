package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class EbayOAuthTokenClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    private final EbayProperties properties;
    private final OkHttpClient httpClient;
    private final HttpUrl tokenEndpoint;
    private final HttpUrl identityEndpoint;

    @Autowired
    public EbayOAuthTokenClient(EbayProperties properties) {
        this(properties, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build(), properties.getTokenEndpoint(), properties.getIdentityApiEndpoint());
    }

    EbayOAuthTokenClient(EbayProperties properties, OkHttpClient httpClient, String tokenEndpoint) {
        this(properties, httpClient, tokenEndpoint, properties.getIdentityApiEndpoint());
    }

    EbayOAuthTokenClient(EbayProperties properties, OkHttpClient httpClient,
                         String tokenEndpoint, String identityEndpoint) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.tokenEndpoint = requireSecureEndpoint(tokenEndpoint, "token");
        this.identityEndpoint = requireSecureEndpoint(identityEndpoint, "identity");
    }

    public JSONObject exchangeAuthorizationCode(String authorizationCode, String ruName) {
        FormBody form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authorizationCode)
                .add("redirect_uri", ruName)
                .build();
        return requestToken(form);
    }

    public JSONObject refreshAccessToken(String refreshToken, String scopes) {
        FormBody form = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("scope", scopes)
                .build();
        return requestToken(form);
    }

    public JSONObject requestApplicationToken(String scope) {
        FormBody form = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("scope", scope)
                .build();
        return requestToken(form);
    }

    public String getUserId(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("eBay access token is required");
        }
        Request request = new Request.Builder()
                .url(identityEndpoint)
                .header("Accept", JSON_MEDIA_TYPE.toString())
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseText = body == null ? "" : body.string();
            JSONObject json = responseText.isBlank() ? new JSONObject() : JSON.parseObject(responseText);
            String userId = json == null ? null : json.getString("userId");
            if (!response.isSuccessful() || userId == null || userId.isBlank()) {
                throw new IllegalStateException(
                        "eBay identity request failed (HTTP " + response.code() + ")");
            }
            return userId.trim();
        } catch (IOException e) {
            throw new IllegalStateException("eBay identity request failed due to a network error", e);
        }
    }

    private JSONObject requestToken(RequestBody form) {
        Request request = new Request.Builder()
                .url(tokenEndpoint)
                .header("Accept", JSON_MEDIA_TYPE.toString())
                .header("Authorization", Credentials.basic(properties.getClientId(), properties.getClientSecret()))
                .post(form)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseText = body == null ? "" : body.string();
            JSONObject json = responseText.isBlank() ? new JSONObject() : JSON.parseObject(responseText);
            if (!response.isSuccessful() || json == null || json.getString("access_token") == null) {
                String error = json == null ? null : json.getString("error");
                String description = json == null ? null : json.getString("error_description");
                throw new IllegalStateException("eBay token request failed (HTTP " + response.code() + "): "
                        + safeMessage(error, description));
            }
            return json;
        } catch (IOException e) {
            throw new IllegalStateException("eBay token request failed: " + e.getMessage(), e);
        }
    }

    private String safeMessage(String error, String description) {
        if (description != null && !description.isBlank()) {
            return description;
        }
        if (error != null && !error.isBlank()) {
            return error;
        }
        return "unexpected response";
    }

    private boolean isLoopbackHttp(HttpUrl url) {
        return "http".equals(url.scheme())
                && ("localhost".equals(url.host()) || "127.0.0.1".equals(url.host()));
    }

    private HttpUrl requireSecureEndpoint(String endpoint, String label) {
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null || !("https".equals(parsed.scheme()) || isLoopbackHttp(parsed))) {
            throw new IllegalArgumentException("eBay " + label + " endpoint must use HTTPS");
        }
        return parsed;
    }
}
