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
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * eBay Trading API（XML）只读调用：订单、留下的信用评价、运送服务名称。
 *
 * <p>订单主体走 Trading API（只需通用 scope）；买家/课税地址、促销标记与发货时间
 * 由 {@link EbayFulfillmentApiClient} 补充（需要 sell.fulfillment.readonly 授权）。
 */
@Component
public class EbayTradingApiClient {

    private static final MediaType XML_MEDIA_TYPE = MediaType.get("text/xml; charset=utf-8");
    private static final Set<String> SUCCESS_ACKS = Set.of("Success", "Warning");
    private static final String COMPATIBILITY_LEVEL = "1423";
    private static final int MAX_ATTEMPTS = 3;
    /** GetOrders 每页上限 100。 */
    public static final int ORDERS_PAGE_SIZE = 100;
    /** GetOrders 的 CreateTimeFrom 最多回溯 90 天。 */
    public static final int MAX_ORDER_DAYS = 90;
    private static final int FEEDBACK_PAGE_SIZE = 200;
    private static final int MAX_FEEDBACK_PAGES = 50;

    private final EbayOAuthService oauthService;
    private final OkHttpClient httpClient;
    private final HttpUrl endpoint;

    @Autowired
    public EbayTradingApiClient(EbayProperties properties, EbayOAuthService oauthService) {
        this(oauthService, new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                properties.getTradingApiEndpoint());
    }

    EbayTradingApiClient(EbayOAuthService oauthService, OkHttpClient httpClient, String endpoint) {
        this.oauthService = oauthService;
        this.httpClient = httpClient;
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null || !("https".equals(parsed.scheme())
                || ("http".equals(parsed.scheme())
                && ("localhost".equals(parsed.host()) || "127.0.0.1".equals(parsed.host()))))) {
            throw new IllegalArgumentException("eBay Trading API endpoint must use HTTPS");
        }
        this.endpoint = parsed;
    }

    public record OrdersPage(List<Element> orders, boolean hasMore, int totalPages, int totalEntries) {
    }

    /** 按创建时间拉取一页卖家订单（全部状态，含明细）。 */
    public OrdersPage getOrders(Instant createdFrom, Instant createdTo, int pageNumber) {
        String payload = """
                <?xml version="1.0" encoding="utf-8"?>
                <GetOrdersRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                  <CreateTimeFrom>%s</CreateTimeFrom>
                  <CreateTimeTo>%s</CreateTimeTo>
                  <OrderRole>Seller</OrderRole>
                  <OrderStatus>All</OrderStatus>
                  <DetailLevel>ReturnAll</DetailLevel>
                  <Pagination>
                    <EntriesPerPage>%d</EntriesPerPage>
                    <PageNumber>%d</PageNumber>
                  </Pagination>
                </GetOrdersRequest>
                """.formatted(createdFrom.truncatedTo(ChronoUnit.SECONDS),
                createdTo.truncatedTo(ChronoUnit.SECONDS), ORDERS_PAGE_SIZE, pageNumber);
        Document document = call("GetOrders", payload);
        List<Element> orders = new ArrayList<>();
        Element orderArray = firstElement(document.getDocumentElement(), "OrderArray");
        if (orderArray != null) {
            for (Node node = orderArray.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && "Order".equals(element.getLocalName())) {
                    orders.add(element);
                }
            }
        }
        Element root = document.getDocumentElement();
        return new OrdersPage(orders,
                "true".equalsIgnoreCase(text(root, "HasMoreOrders")),
                parseInt(text(root, "PaginationResult/TotalNumberOfPages")),
                parseInt(text(root, "PaginationResult/TotalNumberOfEntries")));
    }

    /** GetSellerList 每页上限 200。 */
    private static final int SELLER_LIST_PAGE_SIZE = 200;
    private static final int MAX_SELLER_LIST_PAGES = 500;

    /** eBay 上一个 SKU 的实时价格与可售数量（可售 = Quantity - QuantitySold）。 */
    public record ActiveSku(String sku, String listingId, java.math.BigDecimal price, int availableQuantity) {
    }

    /** GetSellerList 的一页：页码从 1 开始，totalPages 为 eBay 当次返回的总页数。 */
    public record ActiveSkuPage(int pageNumber, int totalPages, List<ActiveSku> skus) {
    }

    /**
     * 用 GetSellerList 读取全部在架商品的 SKU 实时价格与可售数量（含多属性商品的每个尺码）。
     * 结束时间窗口取未来 119 天：GTC 商品每 30 天续期，都会落在窗口内。
     */
    public List<ActiveSku> getActiveSkus(java.util.function.BooleanSupplier cancelled) {
        List<ActiveSku> result = new ArrayList<>();
        forEachActiveSkuPage(cancelled, page -> result.addAll(page.skus()));
        return result;
    }

    /**
     * 逐页读取在架商品，每读到一页立即交给 consumer 处理，处理完再读下一页，
     * 让调用方按接近实时的价格/库存处理，不必等全部页读完。consumer 抛出的异常直接向上传播并终止翻页。
     */
    public void forEachActiveSkuPage(java.util.function.BooleanSupplier cancelled,
                                     java.util.function.Consumer<ActiveSkuPage> consumer) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        StringBuilder selectors = new StringBuilder();
        for (String selector : List.of("ItemArray.Item.ItemID", "ItemArray.Item.SKU",
                "ItemArray.Item.StartPrice", "ItemArray.Item.Quantity",
                "ItemArray.Item.SellingStatus.QuantitySold",
                "ItemArray.Item.Variations.Variation.SKU", "ItemArray.Item.Variations.Variation.StartPrice",
                "ItemArray.Item.Variations.Variation.Quantity",
                "ItemArray.Item.Variations.Variation.SellingStatus.QuantitySold",
                "PaginationResult", "HasMoreItems")) {
            selectors.append("  <OutputSelector>").append(selector).append("</OutputSelector>\n");
        }
        for (int page = 1; page <= MAX_SELLER_LIST_PAGES; page++) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("cancelled");
            }
            String payload = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <GetSellerListRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                      <EndTimeFrom>%s</EndTimeFrom>
                      <EndTimeTo>%s</EndTimeTo>
                      <GranularityLevel>Coarse</GranularityLevel>
                      <IncludeVariations>true</IncludeVariations>
                      <Pagination>
                        <EntriesPerPage>%d</EntriesPerPage>
                        <PageNumber>%d</PageNumber>
                      </Pagination>
                    %s</GetSellerListRequest>
                    """.formatted(now, now.plus(119, ChronoUnit.DAYS), SELLER_LIST_PAGE_SIZE, page, selectors);
            Document document = call("GetSellerList", payload);
            Element root = document.getDocumentElement();
            List<Element> items = children(firstElement(root, "ItemArray"), "Item");
            List<ActiveSku> skus = new ArrayList<>();
            for (Element item : items) {
                String itemId = text(item, "ItemID");
                List<Element> variations = children(firstElement(item, "Variations"), "Variation");
                if (variations.isEmpty()) {
                    addActiveSku(skus, text(item, "SKU"), itemId, text(item, "StartPrice"),
                            text(item, "Quantity"), text(item, "SellingStatus/QuantitySold"));
                }
                for (Element variation : variations) {
                    addActiveSku(skus, text(variation, "SKU"), itemId, text(variation, "StartPrice"),
                            text(variation, "Quantity"), text(variation, "SellingStatus/QuantitySold"));
                }
            }
            int totalPages = parseInt(text(root, "PaginationResult/TotalNumberOfPages"));
            if (items.isEmpty()) {
                break;
            }
            consumer.accept(new ActiveSkuPage(page, Math.max(totalPages, page), List.copyOf(skus)));
            if (page >= totalPages) {
                break;
            }
        }
    }

    private static void addActiveSku(List<ActiveSku> result, String sku, String itemId, String price,
                                     String quantity, String sold) {
        if (sku == null || itemId == null || price == null) {
            return;
        }
        try {
            int available = Math.max(0, parseInt(quantity) - parseInt(sold));
            result.add(new ActiveSku(sku, itemId, new java.math.BigDecimal(price), available));
        } catch (NumberFormatException ignored) {
            // 价格异常的 SKU 跳过，不参与改价
        }
    }

    /** GetOrders 按 OrderID 查询时单次最多携带的订单数。 */
    public static final int ORDER_ID_BATCH = 20;

    /** 按订单编号批量查询卖家订单（含明细与已有追踪号码）；单次不超过 {@link #ORDER_ID_BATCH} 个。 */
    public List<Element> getOrdersByIds(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        if (orderIds.size() > ORDER_ID_BATCH) {
            throw new IllegalArgumentException("单次最多查询" + ORDER_ID_BATCH + "个订单");
        }
        StringBuilder ids = new StringBuilder();
        for (String orderId : orderIds) {
            ids.append("    <OrderID>").append(escapeXml(orderId)).append("</OrderID>\n");
        }
        String payload = """
                <?xml version="1.0" encoding="utf-8"?>
                <GetOrdersRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                  <OrderIDArray>
                %s  </OrderIDArray>
                  <OrderRole>Seller</OrderRole>
                  <DetailLevel>ReturnAll</DetailLevel>
                </GetOrdersRequest>
                """.formatted(ids);
        Document document = call("GetOrders", payload);
        return children(firstElement(document.getDocumentElement(), "OrderArray"), "Order");
    }

    public record ShipmentTracking(String trackingNumber, String carrier) {
    }

    /**
     * 给整个订单添加追踪号码并标记为已发货（CompleteSale，只传 OrderID 时作用于订单内全部物品）。
     * 与卖家中心「添加或编辑追踪号码」弹窗效果一致，eBay 会把追踪详情同步给买家。
     */
    public void addTracking(String orderId, List<ShipmentTracking> trackings) {
        if (orderId == null || orderId.isBlank() || trackings == null || trackings.isEmpty()) {
            throw new IllegalArgumentException("订单编号和追踪号码不能为空");
        }
        StringBuilder details = new StringBuilder();
        for (ShipmentTracking tracking : trackings) {
            details.append("""
                        <ShipmentTrackingDetails>
                          <ShipmentTrackingNumber>%s</ShipmentTrackingNumber>
                          <ShippingCarrierUsed>%s</ShippingCarrierUsed>
                        </ShipmentTrackingDetails>
                    """.formatted(escapeXml(tracking.trackingNumber()), escapeXml(tracking.carrier())));
        }
        String payload = """
                <?xml version="1.0" encoding="utf-8"?>
                <CompleteSaleRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                  <OrderID>%s</OrderID>
                  <Shipped>true</Shipped>
                  <Shipment>
                %s  </Shipment>
                </CompleteSaleRequest>
                """.formatted(escapeXml(orderId), details);
        call("CompleteSale", payload);
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 卖家已给买家留下信用评价的 OrderLineItemID 集合。 */
    public Set<String> getFeedbackLeftLineItemIds() {
        Set<String> lineItemIds = new HashSet<>();
        for (int page = 1; page <= MAX_FEEDBACK_PAGES; page++) {
            String payload = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <GetFeedbackRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                      <FeedbackType>FeedbackLeft</FeedbackType>
                      <DetailLevel>ReturnAll</DetailLevel>
                      <Pagination>
                        <EntriesPerPage>%d</EntriesPerPage>
                        <PageNumber>%d</PageNumber>
                      </Pagination>
                    </GetFeedbackRequest>
                    """.formatted(FEEDBACK_PAGE_SIZE, page);
            Document document = call("GetFeedback", payload);
            NodeList details = document.getElementsByTagNameNS("*", "FeedbackDetail");
            for (int i = 0; i < details.getLength(); i++) {
                Element detail = (Element) details.item(i);
                String lineItemId = text(detail, "OrderLineItemID");
                if (lineItemId == null) {
                    String itemId = text(detail, "ItemID");
                    String transactionId = text(detail, "TransactionID");
                    if (itemId != null && transactionId != null) {
                        lineItemId = itemId + "-" + transactionId;
                    }
                }
                if (lineItemId != null) {
                    lineItemIds.add(lineItemId);
                }
            }
            int totalPages = parseInt(text(document.getDocumentElement(),
                    "PaginationResult/TotalNumberOfPages"));
            if (details.getLength() < FEEDBACK_PAGE_SIZE || page >= totalPages) {
                break;
            }
        }
        return lineItemIds;
    }

    /** 运送服务代码 -> 展示名称，例如 ExpressInternationalShipping_2 -> Express International Shipping。 */
    public Map<String, String> getShippingServiceDescriptions() {
        Document document = call("GeteBayDetails", """
                <?xml version="1.0" encoding="utf-8"?>
                <GeteBayDetailsRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                  <DetailName>ShippingServiceDetails</DetailName>
                </GeteBayDetailsRequest>
                """);
        Map<String, String> descriptions = new LinkedHashMap<>();
        NodeList details = document.getElementsByTagNameNS("*", "ShippingServiceDetails");
        for (int i = 0; i < details.getLength(); i++) {
            Element detail = (Element) details.item(i);
            String code = text(detail, "ShippingService");
            String description = text(detail, "Description");
            if (code != null && description != null) {
                descriptions.putIfAbsent(code, description);
            }
        }
        return descriptions;
    }

    private Document call(String callName, String payload) {
        for (int attempt = 1; ; attempt++) {
            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Accept", "text/xml")
                    .header("X-EBAY-API-CALL-NAME", callName)
                    .header("X-EBAY-API-COMPATIBILITY-LEVEL", COMPATIBILITY_LEVEL)
                    .header("X-EBAY-API-SITEID", "0")
                    .header("X-EBAY-API-IAF-TOKEN", oauthService.getValidAccessToken())
                    .post(RequestBody.create(XML_MEDIA_TYPE, payload))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseText = body == null ? "" : body.string();
                if (response.code() >= 500 && attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                if (!response.isSuccessful()) {
                    throw new EbayApiException("eBay " + callName + "失败(HTTP " + response.code() + ")");
                }
                Document document = parseXml(responseText, callName);
                String ack = text(document.getDocumentElement(), "Ack");
                if (!SUCCESS_ACKS.contains(ack)) {
                    throw new EbayApiException("eBay " + callName + "失败：" + firstError(document));
                }
                return document;
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                throw new EbayApiException("eBay " + callName + "失败：网络异常", e);
            }
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EbayApiException("eBay Trading API retry interrupted", e);
        }
    }

    private String firstError(Document document) {
        NodeList errors = document.getElementsByTagNameNS("*", "Errors");
        for (int i = 0; i < errors.getLength(); i++) {
            Element error = (Element) errors.item(i);
            if (!"Error".equals(text(error, "SeverityCode"))) {
                continue;
            }
            String code = text(error, "ErrorCode");
            String message = text(error, "ShortMessage");
            if (message == null) {
                message = text(error, "LongMessage");
            }
            String summary = (code == null ? "" : code + " ") + (message == null ? "" : message);
            return summary.length() <= 200 ? summary : summary.substring(0, 200);
        }
        return "未知错误";
    }

    private Document parseXml(String xml, String callName) {
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
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new EbayApiException("eBay " + callName + "失败：响应格式异常", e);
        }
    }

    // ==================== XML 路径工具（按 localName 逐级查找直接子元素） ====================

    public static Element firstElement(Element parent, String path) {
        if (parent == null || path == null) {
            return null;
        }
        Element current = parent;
        for (String segment : path.split("/")) {
            Element next = null;
            for (Node node = current.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && segment.equals(element.getLocalName())) {
                    next = element;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    public static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    /** 路径下第一个元素的去空白文本，不存在或为空时返回 null。 */
    public static String text(Element parent, String path) {
        Element element = firstElement(parent, path);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
