package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class EbayOAuthTokenClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    private final EbayProperties properties;
    private final OkHttpClient httpClient;

    public EbayOAuthTokenClient(EbayProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
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

    private JSONObject requestToken(RequestBody form) {
        Request request = new Request.Builder()
                .url(properties.getTokenEndpoint())
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
}
