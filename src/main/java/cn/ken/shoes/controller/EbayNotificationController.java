package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayAccountDeletionNotificationService;
import cn.ken.shoes.service.EbayNotificationChallengeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("ebay/notifications/account-deletion")
public class EbayNotificationController {

    private static final int MAX_NOTIFICATION_BODY_BYTES = 64 * 1024;

    private final EbayNotificationChallengeService challengeService;
    private final EbayAccountDeletionNotificationService notificationService;

    public EbayNotificationController(
            EbayNotificationChallengeService challengeService,
            EbayAccountDeletionNotificationService notificationService) {
        this.challengeService = challengeService;
        this.notificationService = notificationService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> verifyEndpoint(
            @RequestParam("challenge_code") String challengeCode) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("challengeResponse",
                            challengeService.challengeResponse(challengeCode)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receiveNotification(
            @RequestHeader("X-EBAY-SIGNATURE") String signature,
            @RequestBody byte[] payload) {
        if (payload == null || payload.length == 0) {
            return ResponseEntity.badRequest().build();
        }
        if (payload.length > MAX_NOTIFICATION_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }
        try {
            notificationService.verifyAndProcess(signature, payload);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(412).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
