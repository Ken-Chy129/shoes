package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayNotificationChallengeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("ebay/notifications/account-deletion")
public class EbayNotificationController {

    private final EbayNotificationChallengeService challengeService;

    public EbayNotificationController(EbayNotificationChallengeService challengeService) {
        this.challengeService = challengeService;
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
}
