package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class EbayNotificationChallengeService {

    private static final int MAX_CHALLENGE_LENGTH = 512;
    private static final Pattern VERIFICATION_TOKEN =
            Pattern.compile("[A-Za-z0-9_-]{32,80}");

    private final EbayProperties properties;

    public EbayNotificationChallengeService(EbayProperties properties) {
        this.properties = properties;
    }

    public String challengeResponse(String challengeCode) {
        if (challengeCode == null || challengeCode.isBlank()
                || challengeCode.length() > MAX_CHALLENGE_LENGTH) {
            throw new IllegalArgumentException("invalid challenge code");
        }
        String endpoint = requireSecureEndpoint(properties.getNotificationEndpoint());
        String verificationToken = properties.getNotificationVerificationToken();
        if (verificationToken == null
                || !VERIFICATION_TOKEN.matcher(verificationToken).matches()) {
            throw new IllegalStateException("eBay notification verification token is not configured");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(challengeCode.getBytes(StandardCharsets.UTF_8));
            digest.update(verificationToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(
                    digest.digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String requireSecureEndpoint(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("eBay notification endpoint is not configured");
        }
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("eBay notification endpoint is invalid", e);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalStateException("eBay notification endpoint must be public HTTPS");
        }
        return value;
    }
}
