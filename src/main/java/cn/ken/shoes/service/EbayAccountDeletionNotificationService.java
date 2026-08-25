package cn.ken.shoes.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

@Slf4j
@Service
public class EbayAccountDeletionNotificationService {

    private static final String ACCOUNT_DELETION_TOPIC =
            "MARKETPLACE_ACCOUNT_DELETION";

    private final EbayNotificationSignatureVerifier signatureVerifier;
    private final EbayOAuthService oauthService;

    public EbayAccountDeletionNotificationService(
            EbayNotificationSignatureVerifier signatureVerifier,
            EbayOAuthService oauthService) {
        this.signatureVerifier = signatureVerifier;
        this.oauthService = oauthService;
    }

    public void verifyAndProcess(String signatureHeader, byte[] payload) {
        if (!signatureVerifier.verify(signatureHeader, payload)) {
            throw new SecurityException("invalid eBay notification signature");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid eBay notification payload", e);
        }
        JSONObject metadata = root == null ? null : root.getJSONObject("metadata");
        JSONObject notification = root == null ? null : root.getJSONObject("notification");
        String topic = metadata == null ? null : metadata.getString("topic");
        String notificationId = notification == null
                ? null : notification.getString("notificationId");
        String eventDate = notification == null ? null : notification.getString("eventDate");
        if (!ACCOUNT_DELETION_TOPIC.equals(topic)
                || notificationId == null || notificationId.isBlank()
                || eventDate == null || eventDate.isBlank()) {
            throw new IllegalArgumentException("unexpected eBay notification payload");
        }
        long eventOccurredAt;
        try {
            eventOccurredAt = Instant.parse(eventDate).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid eBay notification event date", e);
        }
        if (oauthService.clearAuthorizationForDeletionEvent(eventOccurredAt)) {
            log.info("Processed verified eBay marketplace account deletion notification");
        } else {
            log.info("Acknowledged stale or already processed eBay marketplace account deletion notification");
        }
    }
}
