package cn.ken.shoes.client;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.service.EbayOAuthService;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class EbayPictureApiClient {

    private static final MediaType XML_MEDIA_TYPE =
            MediaType.get("text/xml; charset=utf-8");
    private static final Set<String> SUCCESS_ACKS = Set.of("Success", "Warning");

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl endpoint;

    @Autowired
    public EbayPictureApiClient(EbayProperties properties,
                                EbayOAuthService oauthService) {
        this(properties, oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getTradingApiEndpoint());
    }

    EbayPictureApiClient(EbayProperties properties, EbayOAuthService oauthService,
                         OkHttpClient httpClient, String endpoint) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        this.endpoint = requireSecureEndpoint(endpoint);
    }

    public String uploadExternalPicture(String imageUrl, String pictureName) {
        String payload = """
                <?xml version="1.0" encoding="utf-8"?>
                <UploadSiteHostedPicturesRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                  <PictureName>%s</PictureName>
                  <PictureSet>Supersize</PictureSet>
                  <ExternalPictureURL>%s</ExternalPictureURL>
                </UploadSiteHostedPicturesRequest>
                """.formatted(xmlEscape(pictureName), xmlEscape(imageUrl));
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Accept", "text/xml")
                .header("Content-Type", XML_MEDIA_TYPE.toString())
                .header("X-EBAY-API-CALL-NAME", "UploadSiteHostedPictures")
                .header("X-EBAY-API-COMPATIBILITY-LEVEL", "1423")
                .header("X-EBAY-API-SITEID", "0")
                .header("X-EBAY-API-IAF-TOKEN", oauthService.getValidAccessToken())
                .post(RequestBody.create(XML_MEDIA_TYPE, payload))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseText = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new EbayApiException(
                        "eBay图片托管失败(HTTP " + response.code() + ")");
            }
            Document document = parseXml(responseText);
            String ack = firstText(document, "Ack");
            String fullUrl = firstText(document, "FullURL");
            if (!SUCCESS_ACKS.contains(ack) || fullUrl == null || fullUrl.isBlank()) {
                throw new EbayApiException("eBay图片托管失败：未返回托管地址");
            }
            return fullUrl.trim();
        } catch (IOException e) {
            throw new EbayApiException("eBay图片托管失败：网络异常", e);
        }
    }

    private Document parseXml(String xml) {
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
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | org.xml.sax.SAXException | IOException e) {
            throw new EbayApiException("eBay图片托管失败：响应格式异常", e);
        }
    }

    private String firstText(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private HttpUrl requireSecureEndpoint(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !("https".equals(parsed.scheme()) || isLoopbackHttp(parsed))) {
            throw new IllegalArgumentException("eBay Trading API endpoint must use HTTPS");
        }
        return parsed;
    }

    private boolean isLoopbackHttp(HttpUrl url) {
        return "http".equals(url.scheme())
                && ("localhost".equals(url.host()) || "127.0.0.1".equals(url.host()));
    }
}
