package cn.ken.shoes.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayAccountDeletionNotificationServiceTest {

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

        verify(oauthService).clearAuthorization();
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
        verify(oauthService, never()).clearAuthorization();
    }

    private byte[] validPayload() {
        return ("{\"metadata\":{\"topic\":\"MARKETPLACE_ACCOUNT_DELETION\"},"
                + "\"notification\":{\"notificationId\":\"notification-123\","
                + "\"data\":{\"userId\":\"user-123\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
