package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayNotificationPublicKeyClient;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayNotificationSignatureVerifierTest {

    @Test
    void verifiesEbayEccSignatureAndCachesThePublicKey() throws Exception {
        KeyPair keyPair = keyPair();
        byte[] payload = "{\"metadata\":{\"topic\":\"MARKETPLACE_ACCOUNT_DELETION\"}}"
                .getBytes(StandardCharsets.UTF_8);
        String signatureHeader = signatureHeader("key-123", keyPair, payload);
        EbayNotificationPublicKeyClient keyClient = mock(EbayNotificationPublicKeyClient.class);
        when(keyClient.getPublicKey("key-123")).thenReturn(
                new EbayNotificationPublicKeyClient.PublicKeyData(
                        pem(keyPair), "ECDSA", "SHA256"));
        EbayNotificationSignatureVerifier verifier =
                new EbayNotificationSignatureVerifier(keyClient, () -> 1_000L);

        assertThat(verifier.verify(signatureHeader, payload)).isTrue();
        assertThat(verifier.verify(signatureHeader, payload)).isTrue();
        verify(keyClient, times(1)).getPublicKey("key-123");
    }

    @Test
    void rejectsTamperedPayloadAndMalformedHeaders() throws Exception {
        KeyPair keyPair = keyPair();
        byte[] payload = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        EbayNotificationPublicKeyClient keyClient = mock(EbayNotificationPublicKeyClient.class);
        when(keyClient.getPublicKey("key-123")).thenReturn(
                new EbayNotificationPublicKeyClient.PublicKeyData(
                        pem(keyPair), "ECDSA", "SHA256"));
        EbayNotificationSignatureVerifier verifier =
                new EbayNotificationSignatureVerifier(keyClient, () -> 1_000L);

        assertThat(verifier.verify(signatureHeader("key-123", keyPair, payload),
                "{\"value\":2}".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(verifier.verify("not-base64", payload)).isFalse();
        assertThat(verifier.verify(null, payload)).isFalse();
    }

    @Test
    void verifiesSignatureAgainstCanonicalJsonLikeTheOfficialEbaySdk()
            throws Exception {
        KeyPair keyPair = keyPair();
        byte[] canonicalPayload = ("{\"metadata\":{\"topic\":"
                + "\"MARKETPLACE_ACCOUNT_DELETION\",\"schemaVersion\":\"1.0\","
                + "\"deprecated\":false},\"notification\":{\"notificationId\":"
                + "\"notification-123\"}}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] formattedHttpPayload = ("{\n"
                + "  \"metadata\": {\n"
                + "    \"topic\": \"MARKETPLACE_ACCOUNT_DELETION\",\n"
                + "    \"schemaVersion\": \"1.0\",\n"
                + "    \"deprecated\": false\n"
                + "  },\n"
                + "  \"notification\": {\n"
                + "    \"notificationId\": \"notification-123\"\n"
                + "  }\n"
                + "}").getBytes(StandardCharsets.UTF_8);
        EbayNotificationPublicKeyClient keyClient = mock(EbayNotificationPublicKeyClient.class);
        when(keyClient.getPublicKey("key-123")).thenReturn(
                new EbayNotificationPublicKeyClient.PublicKeyData(
                        pem(keyPair), "ECDSA", "SHA256"));
        EbayNotificationSignatureVerifier verifier =
                new EbayNotificationSignatureVerifier(keyClient, () -> 1_000L);

        assertThat(verifier.verify(
                signatureHeader("key-123", keyPair, canonicalPayload),
                formattedHttpPayload)).isTrue();
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private String signatureHeader(String keyId, KeyPair keyPair, byte[] payload)
            throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(payload);
        JSONObject header = new JSONObject(true);
        header.put("kid", keyId);
        header.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return Base64.getEncoder().encodeToString(
                header.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private String pem(KeyPair keyPair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
