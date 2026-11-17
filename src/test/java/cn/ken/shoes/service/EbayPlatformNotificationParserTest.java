package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EbayPlatformNotificationParserTest {

    private static final String TIMESTAMP = "2026-11-18T01:00:00.123Z";

    @Test
    void acceptsCorrectlySignedAskSellerQuestion() {
        EbayPlatformNotificationParser parser = parser(Instant.parse("2026-11-18T01:05:00Z"));
        String signature = EbayPlatformNotificationParser.sign(TIMESTAMP, "dev", "app", "cert");

        var notification = parser.parseAndVerify(soap(signature, TIMESTAMP));

        assertThat(notification.eventName()).isEqualTo("AskSellerQuestion");
        assertThat(notification.recipientUserId()).isEqualTo("seller1");
        assertThat(notification.response().getLocalName()).isEqualTo("GetMemberMessagesResponse");
    }

    @Test
    void signatureIsBase64Md5OfTimestampAndKeys() {
        // echo -n "2026-11-18T01:00:00.123Zdevappcert" | openssl md5 -binary | base64
        assertThat(EbayPlatformNotificationParser.sign(TIMESTAMP, "dev", "app", "cert"))
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(md5(TIMESTAMP + "devappcert")));
    }

    @Test
    void rejectsWrongSignature() {
        EbayPlatformNotificationParser parser = parser(Instant.parse("2026-11-18T01:05:00Z"));
        String signature = EbayPlatformNotificationParser.sign(TIMESTAMP, "dev", "app", "other");

        assertThatThrownBy(() -> parser.parseAndVerify(soap(signature, TIMESTAMP)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsReplayOlderThanTenMinutes() {
        EbayPlatformNotificationParser parser = parser(Instant.parse("2026-11-18T01:11:00Z"));
        String signature = EbayPlatformNotificationParser.sign(TIMESTAMP, "dev", "app", "cert");

        assertThatThrownBy(() -> parser.parseAndVerify(soap(signature, TIMESTAMP)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void refusesEverythingWhenDevIdIsMissing() {
        EbayProperties properties = properties();
        properties.getMessages().setDevId("");
        EbayPlatformNotificationParser parser = new EbayPlatformNotificationParser(properties,
                Clock.fixed(Instant.parse("2026-11-18T01:05:00Z"), ZoneOffset.UTC));
        String signature = EbayPlatformNotificationParser.sign(TIMESTAMP, "", "app", "cert");

        assertThatThrownBy(() -> parser.parseAndVerify(soap(signature, TIMESTAMP)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsDoctypeAndNonSoapPayloads() {
        EbayPlatformNotificationParser parser = parser(Instant.parse("2026-11-18T01:05:00Z"));

        assertThatThrownBy(() -> parser.parseAndVerify(
                "<!DOCTYPE x [<!ENTITY a SYSTEM \"file:///etc/passwd\">]><x>&a;</x>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseAndVerify("<x/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static byte[] soap(String signature, String timestamp) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Header>
                    <ebl:RequesterCredentials soapenv:mustUnderstand="0" xmlns:ebl="urn:ebay:apis:eBLBaseComponents">
                      <ebl:NotificationSignature>%s</ebl:NotificationSignature>
                    </ebl:RequesterCredentials>
                  </soapenv:Header>
                  <soapenv:Body>
                    <GetMemberMessagesResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                      <Timestamp>%s</Timestamp>
                      <Ack>Success</Ack>
                      <NotificationEventName>AskSellerQuestion</NotificationEventName>
                      <RecipientUserId>seller1</RecipientUserId>
                      <MemberMessage>
                        <MemberMessageExchange>
                          <Item><ItemID>123456</ItemID><Title>Air Jordan 1</Title></Item>
                          <Question>
                            <MessageType>AskSellerQuestion</MessageType>
                            <QuestionType>Shipping</QuestionType>
                            <SenderID>buyer1</SenderID>
                            <Subject>Question</Subject>
                            <Body>When will it ship?</Body>
                            <MessageID>695</MessageID>
                          </Question>
                          <MessageStatus>Unanswered</MessageStatus>
                          <CreationDate>2026-11-18T00:59:00.000Z</CreationDate>
                        </MemberMessageExchange>
                      </MemberMessage>
                    </GetMemberMessagesResponse>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(signature, timestamp).getBytes(StandardCharsets.UTF_8);
    }

    private static EbayPlatformNotificationParser parser(Instant now) {
        return new EbayPlatformNotificationParser(properties(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static EbayProperties properties() {
        EbayProperties properties = new EbayProperties();
        properties.setClientId("app");
        properties.setClientSecret("cert");
        properties.getMessages().setDevId("dev");
        return properties;
    }

    private static byte[] md5(String value) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
