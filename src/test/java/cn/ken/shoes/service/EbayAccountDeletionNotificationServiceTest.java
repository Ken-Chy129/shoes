package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayOAuthTokenClient;
import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayAccountDeletionNotificationServiceTest {

    private static final long AUTHORIZED_AT = Instant.parse("2027-01-15T08:00:00Z").toEpochMilli();

    @Test
    void clearsSingleSellerAuthorizationAfterAValidDeletionNotification() {
        EbayNotificationSignatureVerifier verifier =
                mock(EbayNotificationSignatureVerifier.class);
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        byte[] payload = validPayload();
        when(verifier.verify("signature", payload)).thenReturn(true);
        EbayAccountDeletionNotificationService service =
                new EbayAccountDeletionNotificationService(verifier, oauthService);

        service.verifyAndProcess("signature", payload);

        verify(oauthService).clearAuthorizationForDeletionEvent(
                "user-123",
                Instant.parse("2027-01-15T08:01:00Z").toEpochMilli());
    }

    @Test
    void rejectsUnverifiedOrUnexpectedNotificationsWithoutDeletingAnything() {
        EbayNotificationSignatureVerifier verifier =
                mock(EbayNotificationSignatureVerifier.class);
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        byte[] payload = validPayload();
        byte[] unexpectedPayload = "{\"metadata\":{\"topic\":\"OTHER\"}}"
                .getBytes(StandardCharsets.UTF_8);
        when(verifier.verify("bad-signature", payload)).thenReturn(false);
        when(verifier.verify("signature", unexpectedPayload)).thenReturn(true);
        EbayAccountDeletionNotificationService service =
                new EbayAccountDeletionNotificationService(verifier, oauthService);

        assertThatThrownBy(() -> service.verifyAndProcess("bad-signature", payload))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.verifyAndProcess(
                "signature", unexpectedPayload))
                .isInstanceOf(IllegalArgumentException.class);
        verify(oauthService, never()).clearAuthorizationForDeletionEvent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void keepsAuthorizationGrantedAfterAStaleDeletionEvent() {
        EbayNotificationSignatureVerifier verifier =
                mock(EbayNotificationSignatureVerifier.class);
        EbayOAuthService oauthService = authorizedOAuthService();
        byte[] payload = validPayload("2027-01-15T07:59:00Z");
        when(verifier.verify("signature", payload)).thenReturn(true);
        EbayAccountDeletionNotificationService service =
                new EbayAccountDeletionNotificationService(verifier, oauthService);

        service.verifyAndProcess("signature", payload);

        assertThat(oauthService.getStatus().getBooleanValue("hasAccessToken")).isTrue();
        assertThat(oauthService.getStatus().getBooleanValue("hasRefreshToken")).isTrue();
    }

    @Test
    void clearsAuthorizationGrantedBeforeADeletionEvent() {
        EbayNotificationSignatureVerifier verifier =
                mock(EbayNotificationSignatureVerifier.class);
        EbayOAuthService oauthService = authorizedOAuthService();
        byte[] payload = validPayload("2027-01-15T08:01:00Z");
        when(verifier.verify("signature", payload)).thenReturn(true);
        EbayAccountDeletionNotificationService service =
                new EbayAccountDeletionNotificationService(verifier, oauthService);

        service.verifyAndProcess("signature", payload);

        assertThat(oauthService.getStatus().getBooleanValue("hasAccessToken")).isFalse();
        assertThat(oauthService.getStatus().getBooleanValue("hasRefreshToken")).isFalse();
    }

    @Test
    void keepsAuthorizationWhenDeletionNotificationBelongsToAnotherSeller() {
        EbayNotificationSignatureVerifier verifier =
                mock(EbayNotificationSignatureVerifier.class);
        EbayOAuthService oauthService = authorizedOAuthService();
        byte[] payload = validPayload("other-user", "2027-01-15T08:01:00Z");
        when(verifier.verify("signature", payload)).thenReturn(true);
        EbayAccountDeletionNotificationService service =
                new EbayAccountDeletionNotificationService(verifier, oauthService);

        service.verifyAndProcess("signature", payload);

        assertThat(oauthService.getStatus().getBooleanValue("hasAccessToken")).isTrue();
        assertThat(oauthService.getStatus().getBooleanValue("hasRefreshToken")).isTrue();
    }

    private byte[] validPayload() {
        return validPayload("2027-01-15T08:01:00Z");
    }

    private byte[] validPayload(String eventDate) {
        return validPayload("user-123", eventDate);
    }

    private byte[] validPayload(String userId, String eventDate) {
        return ("{\"metadata\":{\"topic\":\"MARKETPLACE_ACCOUNT_DELETION\"},"
                + "\"notification\":{\"notificationId\":\"notification-123\","
                + "\"eventDate\":\"" + eventDate + "\","
                + "\"data\":{\"userId\":\"" + userId + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private EbayOAuthService authorizedOAuthService() {
        EbayProperties properties = new EbayProperties();
        properties.setEnvironment("sandbox");
        properties.setClientId("sandbox-client-id");
        properties.setClientSecret("sandbox-client-secret");
        properties.setRuName("sandbox-runame");
        properties.setScopes("https://api.ebay.com/oauth/api_scope/sell.inventory");
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadConfig(any())).thenReturn(new Properties());
        EbayOAuthTokenClient tokenClient = mock(EbayOAuthTokenClient.class);
        EbayOAuthService oauthService = new EbayOAuthService(
                properties, configService, tokenClient, () -> AUTHORIZED_AT);
        oauthService.loadOAuthToken();
        JSONObject authorization = oauthService.createAuthorizationRequest();
        String state = queryParameters(authorization.getString("authorizeUrl")).get("state");
        JSONObject tokenResponse = new JSONObject();
        tokenResponse.put("access_token", "access-token");
        tokenResponse.put("refresh_token", "refresh-token");
        tokenResponse.put("expires_in", 7200L);
        tokenResponse.put("refresh_token_expires_in", 47_304_000L);
        when(tokenClient.exchangeAuthorizationCode("authorization-code", "sandbox-runame"))
                .thenReturn(tokenResponse);
        when(tokenClient.getUserId("access-token")).thenReturn("user-123");
        oauthService.exchangeAuthorizationCode("authorization-code", state);
        return oauthService;
    }

    private Map<String, String> queryParameters(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    }
}
