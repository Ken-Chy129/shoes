package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayOAuthTokenClient;
import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public class EbayOAuthService {

    private static final long ACCESS_TOKEN_REFRESH_BUFFER_MS = 2 * 60 * 1000L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EbayProperties properties;
    private final ConfigService configService;
    private final EbayOAuthTokenClient tokenClient;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> pendingStates = new ConcurrentHashMap<>();

    private String accessToken;
    private String refreshToken;
    private String grantedScopes;
    private long accessTokenExpiresAt;
    private long refreshTokenExpiresAt;

    @Autowired
    public EbayOAuthService(EbayProperties properties, ConfigService configService,
                            EbayOAuthTokenClient tokenClient) {
        this(properties, configService, tokenClient, System::currentTimeMillis);
    }

    EbayOAuthService(EbayProperties properties, ConfigService configService,
                     EbayOAuthTokenClient tokenClient, LongSupplier clock) {
        this.properties = properties;
        this.configService = configService;
        this.tokenClient = tokenClient;
        this.clock = clock;
    }

    @PostConstruct
    public synchronized void loadOAuthToken() {
        Properties stored = configService.loadConfig(tokenConfigFile());
        accessToken = stored.getProperty("access.token", "");
        refreshToken = stored.getProperty("refresh.token", "");
        grantedScopes = stored.getProperty("scopes", properties.getScopes());
        accessTokenExpiresAt = parseLong(stored.getProperty("access.token.expires.at"));
        refreshTokenExpiresAt = parseLong(stored.getProperty("refresh.token.expires.at"));
    }

    public synchronized JSONObject createAuthorizationRequest() {
        requireConfigured();
        long now = clock.getAsLong();
        pendingStates.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (pendingStates.size() >= properties.getMaxPendingStates()) {
            throw new IllegalStateException("Too many pending eBay authorization requests");
        }
        String state = newState();
        long stateExpiresAt = now + properties.getStateTtlSeconds() * 1000L;
        pendingStates.put(state, stateExpiresAt);

        String authorizeUrl = UriComponentsBuilder.fromHttpUrl(properties.getAuthorizationEndpoint())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRuName())
                .queryParam("response_type", "code")
                .queryParam("scope", properties.getScopes())
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
        JSONObject response = new JSONObject();
        response.put("authorizeUrl", authorizeUrl);
        response.put("stateExpiresAt", stateExpiresAt);
        return response;
    }

    public synchronized JSONObject exchangeAuthorizationCode(String authorizationCode, String state) {
        requireConfigured();
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new IllegalArgumentException("authorization code is blank");
        }
        validateAndConsumeState(state);
        JSONObject tokenResponse = tokenClient.exchangeAuthorizationCode(authorizationCode, properties.getRuName());
        persistTokenResponse(tokenResponse, true);
        return getStatus();
    }

    public synchronized String getValidAccessToken() {
        requireConfigured();
        long now = clock.getAsLong();
        if (accessToken != null && !accessToken.isBlank()
                && accessTokenExpiresAt > now + ACCESS_TOKEN_REFRESH_BUFFER_MS) {
            return accessToken;
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("eBay seller authorization is required");
        }
        if (refreshTokenExpiresAt > 0 && refreshTokenExpiresAt <= now) {
            throw new IllegalStateException("eBay refresh token has expired; seller authorization is required");
        }
        JSONObject tokenResponse = tokenClient.refreshAccessToken(refreshToken, grantedScopes);
        persistTokenResponse(tokenResponse, false);
        return accessToken;
    }

    public JSONObject getStatus() {
        JSONObject status = new JSONObject();
        status.put("environment", properties.getEnvironment());
        status.put("configured", properties.isConfigured());
        status.put("hasAccessToken", accessToken != null && !accessToken.isBlank());
        status.put("hasRefreshToken", refreshToken != null && !refreshToken.isBlank());
        status.put("accessTokenExpiresAt", accessTokenExpiresAt);
        status.put("refreshTokenExpiresAt", refreshTokenExpiresAt);
        status.put("scopes", grantedScopes == null ? properties.getScopes() : grantedScopes);
        return status;
    }

    public synchronized void clearAuthorization() {
        accessToken = "";
        refreshToken = "";
        grantedScopes = properties.getScopes();
        accessTokenExpiresAt = 0L;
        refreshTokenExpiresAt = 0L;

        Properties stored = new Properties();
        stored.setProperty("access.token", "");
        stored.setProperty("refresh.token", "");
        stored.setProperty("scopes", Objects.toString(grantedScopes, ""));
        stored.setProperty("access.token.expires.at", "0");
        stored.setProperty("refresh.token.expires.at", "0");
        configService.saveSecretConfig(tokenConfigFile(), stored);
    }

    private void validateAndConsumeState(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("OAuth state is blank");
        }
        Long expiresAt = pendingStates.remove(state);
        if (expiresAt == null || expiresAt <= clock.getAsLong()) {
            throw new IllegalArgumentException("OAuth state is invalid or expired");
        }
    }

    private void persistTokenResponse(JSONObject tokenResponse, boolean requireRefreshToken) {
        String newAccessToken = tokenResponse.getString("access_token");
        String newRefreshToken = tokenResponse.getString("refresh_token");
        Long expiresIn = tokenResponse.getLong("expires_in");
        Long refreshExpiresIn = tokenResponse.getLong("refresh_token_expires_in");
        if (newAccessToken == null || newAccessToken.isBlank() || expiresIn == null) {
            throw new IllegalStateException("eBay token response is missing access token fields");
        }
        if (requireRefreshToken && (newRefreshToken == null || newRefreshToken.isBlank())) {
            throw new IllegalStateException("eBay token response is missing refresh token");
        }

        long now = clock.getAsLong();
        accessToken = newAccessToken;
        accessTokenExpiresAt = now + expiresIn * 1000L;
        if (newRefreshToken != null && !newRefreshToken.isBlank()) {
            refreshToken = newRefreshToken;
        }
        if (refreshExpiresIn != null) {
            refreshTokenExpiresAt = now + refreshExpiresIn * 1000L;
        }
        if (requireRefreshToken || grantedScopes == null || grantedScopes.isBlank()) {
            grantedScopes = properties.getScopes();
        }

        Properties stored = new Properties();
        stored.setProperty("access.token", Objects.toString(accessToken, ""));
        stored.setProperty("refresh.token", Objects.toString(refreshToken, ""));
        stored.setProperty("scopes", Objects.toString(grantedScopes, ""));
        stored.setProperty("access.token.expires.at", String.valueOf(accessTokenExpiresAt));
        stored.setProperty("refresh.token.expires.at", String.valueOf(refreshTokenExpiresAt));
        configService.saveSecretConfig(tokenConfigFile(), stored);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("eBay OAuth clientId, clientSecret and ruName must be configured");
        }
    }

    private String tokenConfigFile() {
        return properties.isSandbox() ? "ebay-oauth-sandbox.properties" : "ebay-oauth-production.properties";
    }

    private String newState() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
