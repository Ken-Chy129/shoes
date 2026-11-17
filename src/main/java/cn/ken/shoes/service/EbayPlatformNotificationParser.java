package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.config.EbayProperties;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 解析并校验 Trading API Platform Notifications（SOAP）。
 *
 * <p>签名规则：Base64(MD5(Timestamp + DevID + AppID + CertID))，放在 SOAP Header 的 NotificationSignature；
 * Timestamp 取 Body 里响应的 Timestamp，且必须在当前时间前后 10 分钟内，防止重放。
 */
@Component
public class EbayPlatformNotificationParser {

    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

    public record Notification(String eventName, String recipientUserId, Element response) {
    }

    private final EbayProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public EbayPlatformNotificationParser(EbayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    EbayPlatformNotificationParser(EbayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws SecurityException        签名不对、时间戳过期或未配置签名所需的密钥
     * @throws IllegalArgumentException 不是合法的 SOAP 通知
     */
    public Notification parseAndVerify(byte[] payload) {
        Document document = parse(payload);
        Element envelope = document.getDocumentElement();
        if (envelope == null || !"Envelope".equals(envelope.getLocalName())) {
            throw new IllegalArgumentException("not a SOAP envelope");
        }
        Element body = child(envelope, "Body");
        Element response = firstChildElement(body);
        if (response == null) {
            throw new IllegalArgumentException("empty SOAP body");
        }
        Element header = child(envelope, "Header");
        Element credentials = child(header, "RequesterCredentials");
        String signature = EbayTradingApiClient.text(credentials, "NotificationSignature");
        String timestamp = EbayTradingApiClient.text(response, "Timestamp");
        verify(signature, timestamp);
        return new Notification(EbayTradingApiClient.text(response, "NotificationEventName"),
                EbayTradingApiClient.text(response, "RecipientUserID") != null
                        ? EbayTradingApiClient.text(response, "RecipientUserID")
                        : EbayTradingApiClient.text(response, "RecipientUserId"),
                response);
    }

    private void verify(String signature, String timestamp) {
        String devId = properties.getMessages() == null ? null : properties.getMessages().getDevId();
        String appId = properties.getClientId();
        String certId = properties.getClientSecret();
        if (isBlank(devId) || isBlank(appId) || isBlank(certId)) {
            throw new SecurityException("eBay notification signing keys are not configured");
        }
        if (isBlank(signature) || isBlank(timestamp)) {
            throw new SecurityException("missing eBay notification signature");
        }
        Instant sentAt;
        try {
            sentAt = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new SecurityException("invalid eBay notification timestamp");
        }
        if (Duration.between(sentAt, clock.instant()).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new SecurityException("stale eBay notification");
        }
        byte[] expected = sign(timestamp, devId, appId, certId).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("invalid eBay notification signature");
        }
    }

    static String sign(String timestamp, String devId, String appId, String certId) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((timestamp + devId + appId + certId).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Document parse(byte[] payload) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder.parse(new InputSource(new ByteArrayInputStream(payload)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("invalid eBay notification payload", e);
        }
    }

    private static Element child(Element parent, String localName) {
        return EbayTradingApiClient.firstElement(parent, localName);
    }

    private static Element firstChildElement(Element parent) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
