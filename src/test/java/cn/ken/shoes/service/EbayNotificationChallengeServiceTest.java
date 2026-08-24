package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EbayNotificationChallengeServiceTest {

    @Test
    void hashesChallengeTokenAndExactEndpointInEbayOrder() throws Exception {
        EbayProperties properties = new EbayProperties();
        properties.setNotificationEndpoint(
                "https://shoes.ken-chy129.cn/api/ebay/notifications/account-deletion");
        properties.setNotificationVerificationToken(
                "verification_token_12345678901234567890");
        EbayNotificationChallengeService service =
                new EbayNotificationChallengeService(properties);

        String response = service.challengeResponse("challenge-123");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] expected = digest.digest(("challenge-123"
                + "verification_token_12345678901234567890"
                + "https://shoes.ken-chy129.cn/api/ebay/notifications/account-deletion")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(response).isEqualTo(HexFormat.of().formatHex(expected));
    }

    @Test
    void rejectsMissingOrUnsafeNotificationConfiguration() {
        EbayProperties properties = new EbayProperties();
        EbayNotificationChallengeService service =
                new EbayNotificationChallengeService(properties);

        assertThatThrownBy(() -> service.challengeResponse("challenge-123"))
                .isInstanceOf(IllegalStateException.class);

        properties.setNotificationEndpoint("http://localhost/callback");
        properties.setNotificationVerificationToken("too-short");
        assertThatThrownBy(() -> service.challengeResponse("challenge-123"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankOrOversizedChallengeCodes() {
        EbayProperties properties = new EbayProperties();
        properties.setNotificationEndpoint(
                "https://shoes.ken-chy129.cn/api/ebay/notifications/account-deletion");
        properties.setNotificationVerificationToken(
                "verification_token_12345678901234567890");
        EbayNotificationChallengeService service =
                new EbayNotificationChallengeService(properties);

        assertThatThrownBy(() -> service.challengeResponse(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.challengeResponse("x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
