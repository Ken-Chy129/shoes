package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayNotificationChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayNotificationControllerTest {

    @Test
    void returnsEbayChallengeContractAsJson() {
        EbayNotificationChallengeService challengeService =
                mock(EbayNotificationChallengeService.class);
        when(challengeService.challengeResponse("challenge-123"))
                .thenReturn("abc123");
        EbayNotificationController controller =
                new EbayNotificationController(challengeService);

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
                new EbayNotificationController(challengeService);

        ResponseEntity<Map<String, String>> response =
                controller.verifyEndpoint("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNull();
    }
}
