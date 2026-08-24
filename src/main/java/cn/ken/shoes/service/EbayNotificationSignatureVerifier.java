package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayNotificationPublicKeyClient;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Slf4j
@Service
public class EbayNotificationSignatureVerifier {

    private static final long PUBLIC_KEY_TTL_MS = 60 * 60 * 1000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, String> SIGNATURE_ALGORITHMS = Map.of(
            "SHA1:ECDSA", "SHA1withECDSA",
            "SHA256:ECDSA", "SHA256withECDSA",
            "SHA384:ECDSA", "SHA384withECDSA",
            "SHA512:ECDSA", "SHA512withECDSA",
            "SHA1:EC", "SHA1withECDSA",
            "SHA256:EC", "SHA256withECDSA",
            "SHA384:EC", "SHA384withECDSA",
            "SHA512:EC", "SHA512withECDSA");

    private final EbayNotificationPublicKeyClient keyClient;
    private final LongSupplier clock;
    private final Map<String, CachedPublicKey> keyCache = new ConcurrentHashMap<>();

    @Autowired
    public EbayNotificationSignatureVerifier(EbayNotificationPublicKeyClient keyClient) {
        this(keyClient, System::currentTimeMillis);
    }

    EbayNotificationSignatureVerifier(EbayNotificationPublicKeyClient keyClient,
                                      LongSupplier clock) {
        this.keyClient = keyClient;
        this.clock = clock;
    }

    public boolean verify(String signatureHeader, byte[] payload) {
        if (signatureHeader == null || signatureHeader.isBlank()
                || payload == null || payload.length == 0) {
            return false;
        }
        try {
            JSONObject header = JSON.parseObject(new String(
                    Base64.getDecoder().decode(signatureHeader), StandardCharsets.UTF_8));
            String keyId = header == null ? null : header.getString("kid");
            String encodedSignature = header == null ? null : header.getString("signature");
            if (keyId == null || keyId.isBlank()
                    || encodedSignature == null || encodedSignature.isBlank()) {
                return false;
            }
            EbayNotificationPublicKeyClient.PublicKeyData key = publicKey(keyId);
            String signatureAlgorithm = SIGNATURE_ALGORITHMS.get(
                    key.digest().toUpperCase(Locale.ROOT) + ":"
                            + key.algorithm().toUpperCase(Locale.ROOT));
            if (signatureAlgorithm == null) {
                return false;
            }
            byte[] publicKeyBytes = Base64.getMimeDecoder().decode(stripPem(key.key()));
            java.security.PublicKey publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(publicKey);
            byte[] canonicalPayload = OBJECT_MAPPER.writeValueAsBytes(
                    OBJECT_MAPPER.readTree(payload));
            verifier.update(canonicalPayload);
            return verifier.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (Exception e) {
            log.warn("eBay notification signature validation failed, type:{}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private EbayNotificationPublicKeyClient.PublicKeyData publicKey(String keyId) {
        long now = clock.getAsLong();
        CachedPublicKey cached = keyCache.get(keyId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.key();
        }
        EbayNotificationPublicKeyClient.PublicKeyData fetched = keyClient.getPublicKey(keyId);
        keyCache.put(keyId, new CachedPublicKey(fetched, now + PUBLIC_KEY_TTL_MS));
        return fetched;
    }

    private String stripPem(String value) {
        return value.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private record CachedPublicKey(
            EbayNotificationPublicKeyClient.PublicKeyData key, long expiresAt) {
    }
}
