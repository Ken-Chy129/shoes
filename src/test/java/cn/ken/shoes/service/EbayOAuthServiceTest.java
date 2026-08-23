package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayOAuthTokenClient;
import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayOAuthServiceTest {

    private static final long NOW = 1_800_000_000_000L;

    private EbayProperties properties;
    private ConfigService configService;
    private EbayOAuthTokenClient tokenClient;
    private EbayOAuthService service;

    @BeforeEach
    void setUp() {
        properties = new EbayProperties();
        properties.setEnvironment("sandbox");
        properties.setClientId("sandbox-client-id");
        properties.setClientSecret("sandbox-client-secret");
        properties.setRuName("sandbox-runame");
        properties.setScopes("https://api.ebay.com/oauth/api_scope/sell.inventory "
                + "https://api.ebay.com/oauth/api_scope/sell.account");

        configService = mock(ConfigService.class);
        when(configService.loadConfig(any())).thenReturn(new Properties());
        tokenClient = mock(EbayOAuthTokenClient.class);
        LongSupplier clock = () -> NOW;
        service = new EbayOAuthService(properties, configService, tokenClient, clock);
        service.loadOAuthToken();
    }

    @Test
    void createsSandboxConsentUrlWithMinimalScopesAndSingleUseState() {
        JSONObject authorization = service.createAuthorizationRequest();

        Map<String, String> query = queryParameters(authorization.getString("authorizeUrl"));
        assertThat(query)
                .containsEntry("client_id", "sandbox-client-id")
                .containsEntry("redirect_uri", "sandbox-runame")
                .containsEntry("response_type", "code")
                .containsEntry("scope", properties.getScopes());
        assertThat(query.get("state")).isNotBlank();
        assertThat(authorization.getLongValue("stateExpiresAt")).isGreaterThan(NOW);
    }

    @Test
    void rejectsCallbackWithUnknownStateBeforeCallingEbay() {
        assertThatThrownBy(() -> service.exchangeAuthorizationCode("authorization-code", "unknown-state"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    void boundsPendingAuthorizationStates() {
        properties.setMaxPendingStates(2);
        service.createAuthorizationRequest();
        service.createAuthorizationRequest();

        assertThatThrownBy(service::createAuthorizationRequest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many pending");
    }

    @Test
    void exchangesCodeAndPersistsTokensWithoutReturningSecrets() {
        JSONObject authorization = service.createAuthorizationRequest();
        String state = queryParameters(authorization.getString("authorizeUrl")).get("state");
        JSONObject tokenResponse = new JSONObject();
        tokenResponse.put("access_token", "access-token");
        tokenResponse.put("refresh_token", "refresh-token");
        tokenResponse.put("expires_in", 7200L);
        tokenResponse.put("refresh_token_expires_in", 47_304_000L);
        when(tokenClient.exchangeAuthorizationCode("authorization-code", "sandbox-runame"))
                .thenReturn(tokenResponse);

        JSONObject status = service.exchangeAuthorizationCode("authorization-code", state);

        assertThat(status.getBooleanValue("hasAccessToken")).isTrue();
        assertThat(status.getBooleanValue("hasRefreshToken")).isTrue();
        assertThat(status.toJSONString()).doesNotContain("access-token", "refresh-token", "sandbox-client-secret");
        ArgumentCaptor<Properties> persisted = ArgumentCaptor.forClass(Properties.class);
        verify(configService).saveSecretConfig(any(), persisted.capture());
        assertThat(persisted.getValue().getProperty("access.token")).isEqualTo("access-token");
        assertThat(persisted.getValue().getProperty("refresh.token")).isEqualTo("refresh-token");
    }

    @Test
    void refreshesExpiredAccessTokenAndKeepsExistingRefreshToken() {
        String originallyGrantedScopes = "https://api.ebay.com/oauth/api_scope/sell.inventory";
        Properties stored = new Properties();
        stored.setProperty("access.token", "expired-access-token");
        stored.setProperty("refresh.token", "long-lived-refresh-token");
        stored.setProperty("scopes", originallyGrantedScopes);
        stored.setProperty("access.token.expires.at", String.valueOf(NOW - 1));
        stored.setProperty("refresh.token.expires.at", String.valueOf(NOW + 86_400_000L));
        when(configService.loadConfig(any())).thenReturn(stored);
        service.loadOAuthToken();
        JSONObject refreshResponse = new JSONObject();
        refreshResponse.put("access_token", "fresh-access-token");
        refreshResponse.put("expires_in", 7200L);
        when(tokenClient.refreshAccessToken("long-lived-refresh-token", originallyGrantedScopes))
                .thenReturn(refreshResponse);

        assertThat(service.getValidAccessToken()).isEqualTo("fresh-access-token");

        ArgumentCaptor<Properties> persisted = ArgumentCaptor.forClass(Properties.class);
        verify(configService).saveSecretConfig(any(), persisted.capture());
        assertThat(persisted.getValue().getProperty("refresh.token")).isEqualTo("long-lived-refresh-token");
        assertThat(persisted.getValue().getProperty("scopes")).isEqualTo(originallyGrantedScopes);
    }

    private Map<String, String> queryParameters(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    }
}
