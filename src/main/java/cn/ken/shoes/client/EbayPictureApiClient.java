package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * eBay 图片托管（EPS）。
 *
 * <p>Trading API 的 UploadSiteHostedPictures 已被 eBay 下线（2026-11 起固定返回 HTTP 404），
 * 这里改用 Media API 的 createImageFromUrl：eBay 从外部地址拉图并返回 i.ebayimg.com 地址。
 */
@Component
public class EbayPictureApiClient {

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    /** EPS 地址里带 base64 的 "高X宽"，例如 /00/s/NTAwWDcwMA==/ 即 500X700。 */
    private static final Pattern EPS_DIMENSION = Pattern.compile("/s/([A-Za-z0-9+_=-]+)/");
    private static final Pattern DIMENSION_TEXT = Pattern.compile("(\\d+)X(\\d+)");

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl endpoint;

    @Autowired
    public EbayPictureApiClient(EbayProperties properties,
                                EbayOAuthService oauthService) {
        this(properties, oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getMediaApiEndpoint());
    }

    EbayPictureApiClient(EbayProperties properties, EbayOAuthService oauthService,
                         OkHttpClient httpClient, String endpoint) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        this.endpoint = requireSecureEndpoint(endpoint);
    }

    /**
     * 托管一张外部图片。返回 empty 表示图片最长边不足 500 像素，eBay 不允许作为商品图。
     * pictureName 仅为兼容旧调用保留，Media API 不支持自定义图片名。
     */
    public Optional<String> uploadExternalPicture(String imageUrl, String pictureName) {
        JSONObject payload = new JSONObject().fluentPut("imageUrl", imageUrl);
        HttpUrl url = endpoint.newBuilder().addPathSegment("image")
                .addPathSegment("create_image_from_url").build();
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .header("Authorization", "Bearer " + oauthService.getValidAccessToken())
                .post(RequestBody.create(JSON_MEDIA_TYPE, payload.toJSONString()))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseText = body == null ? "" : body.string();
            if (response.code() == 429) {
                throw new EbayQuotaExceededException(
                        "eBay图片托管失败：图片上传额度已用完(HTTP 429)，请稍后重试");
            }
            if (!response.isSuccessful()) {
                throw uploadFailure(response.code(), responseText);
            }
            JSONObject json = parseJson(responseText);
            String hostedUrl = json == null ? null : json.getString("imageUrl");
            if (hostedUrl == null || hostedUrl.isBlank()) {
                throw new EbayApiException("eBay图片托管失败：未返回托管地址");
            }
            int maxDimension = maxDimension(hostedUrl);
            if (maxDimension > 0 && maxDimension < 500) {
                return Optional.empty();
            }
            return Optional.of(hostedUrl.trim());
        } catch (IOException e) {
            throw new EbayApiException("eBay图片托管失败：网络异常", e);
        }
    }

    private EbayApiException uploadFailure(int status, String responseText) {
        JSONObject json = parseJsonQuietly(responseText);
        JSONArray errors = json == null ? null : json.getJSONArray("errors");
        if (errors != null && !errors.isEmpty() && errors.get(0) instanceof JSONObject error) {
            String code = error.getString("errorId");
            String message = error.getString("message");
            return new EbayApiException("eBay图片托管失败(HTTP " + status + ")："
                    + (code == null ? "" : code + " ")
                    + (message == null ? "" : truncate(message.trim(), 120)));
        }
        return new EbayApiException("eBay图片托管失败(HTTP " + status + ")");
    }

    static int maxDimension(String hostedUrl) {
        Matcher matcher = EPS_DIMENSION.matcher(hostedUrl);
        if (!matcher.find()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(matcher.group(1)),
                    StandardCharsets.US_ASCII);
            Matcher size = DIMENSION_TEXT.matcher(decoded);
            if (!size.matches()) {
                return 0;
            }
            return Math.max(Integer.parseInt(size.group(1)), Integer.parseInt(size.group(2)));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private JSONObject parseJson(String text) {
        try {
            return JSON.parseObject(text);
        } catch (JSONException e) {
            throw new EbayApiException("eBay图片托管失败：响应格式异常", e);
        }
    }

    private JSONObject parseJsonQuietly(String text) {
        try {
            return text == null || text.isBlank() ? null : JSON.parseObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private HttpUrl requireSecureEndpoint(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !("https".equals(parsed.scheme()) || isLoopbackHttp(parsed))) {
            throw new IllegalArgumentException("eBay Media API endpoint must use HTTPS");
        }
        return parsed;
    }

    private boolean isLoopbackHttp(HttpUrl url) {
        return "http".equals(url.scheme())
                && ("localhost".equals(url.host()) || "127.0.0.1".equals(url.host()));
    }
}
