package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayAccountDeletionNotificationService;
import cn.ken.shoes.service.EbayNotificationChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayNotificationControllerTest {

    @Test
    void returnsEbayChallengeContractAsJson() {
        EbayNotificationChallengeService challengeService =
                mock(EbayNotificationChallengeService.class);
        when(challengeService.challengeResponse("challenge-123"))
                .thenReturn("abc123");
        EbayNotificationController controller =
                new EbayNotificationController(challengeService,
                        mock(EbayAccountDeletionNotificationService.class));

        ResponseEntity<Map<String, String>> response =
                controller.verifyEndpoint("challenge-123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody())
                .containsEntry("challengeResponse", "abc123")
                .hasSize(1);
    }

    @Test
    void rejectsInvalidChallengeWithoutLeakingConfiguration() {
        EbayNotificationChallengeService challengeService =
                mock(EbayNotificationChallengeService.class);
        when(challengeService.challengeResponse("bad"))
                .thenThrow(new IllegalArgumentException("internal token detail"));
        EbayNotificationController controller =
                new EbayNotificationController(challengeService,
                        mock(EbayAccountDeletionNotificationService.class));

        ResponseEntity<Map<String, String>> response =
                controller.verifyEndpoint("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void acknowledgesVerifiedDeletionNotificationsWithoutReturningUserData() {
        EbayAccountDeletionNotificationService notificationService =
                mock(EbayAccountDeletionNotificationService.class);
        EbayNotificationController controller = new EbayNotificationController(
                mock(EbayNotificationChallengeService.class), notificationService);
        byte[] payload = "{\"notification\":{}}".getBytes();

        ResponseEntity<Void> response = controller.receiveNotification(
                "signature", payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
        verify(notificationService).verifyAndProcess("signature", payload);
    }

    @Test
    void returnsPreconditionFailedForInvalidSignatures() {
        EbayAccountDeletionNotificationService notificationService =
                mock(EbayAccountDeletionNotificationService.class);
        byte[] payload = "{}".getBytes();
        doThrow(new SecurityException("signature detail"))
                .when(notificationService).verifyAndProcess("bad", payload);
        EbayNotificationController controller = new EbayNotificationController(
                mock(EbayNotificationChallengeService.class), notificationService);

        ResponseEntity<Void> response = controller.receiveNotification("bad", payload);

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void rejectsOversizedBodiesBeforeSignatureVerification() {
        EbayAccountDeletionNotificationService notificationService =
                mock(EbayAccountDeletionNotificationService.class);
        EbayNotificationController controller = new EbayNotificationController(
                mock(EbayNotificationChallengeService.class), notificationService);
        byte[] payload = new byte[65_537];

        ResponseEntity<Void> response = controller.receiveNotification(
                "signature", payload);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }
}
