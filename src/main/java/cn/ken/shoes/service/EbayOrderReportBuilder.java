package cn.ken.shoes.service;

import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.util.SizeConvertUtil;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.ken.shoes.client.EbayTradingApiClient.children;
import static cn.ken.shoes.client.EbayTradingApiClient.firstElement;
import static cn.ken.shoes.client.EbayTradingApiClient.text;

/**
 * 把 Trading API GetOrders 的订单转换成与 eBay 卖家中心「下载报告」相同列顺序的行，
 * 并在末尾追加 货号 / US尺码 / EU码 / 订单日期 / 发货截止日期。
 *
 * <p>报告中的日期按 eBay 美国站的太平洋时间展示（如 Oct-13-26）；追加列使用北京时间。
 */
public class EbayOrderReportBuilder {

    /** 与 eBay 订单报告 CSV 完全一致的列名与顺序。 */
    public static final List<String> REPORT_COLUMNS = List.of(
            "销售记录编号", "订单编号", "买家用户名", "买家姓名", "买家电子邮件", "买家备注",
            "买家地址 1", "买家地址 2", "买家所在县/市", "买家所在州/省", "买家邮政编码", "买家所在国家/地区",
            "买家纳税识别名称", "买家纳税识别值", "收货人姓名", "收货人电话", "收货地址 1", "收货地址 2",
            "收货人所在县/市", "收货人所在州/省", "收货人邮政编码", "收货人所在国家/地区", "物品编号", "物品标题",
            "自定义标签", "通过促销刊登售出", "数量", "成交价", "运费与处理费", "物品所在地", "物品所在地邮政编码",
            "物品所在国家/地区", "eBay 收缴税率", "eBay 收缴税费类型", "eBay 参考名", "eBay 参考值", "税收状态",
            "卖家收取的税费", "eBay 收取的税费", "电子垃圾回收费用", "床垫回收费用", "电池回收费用", "大型家电处置税",
            "轮胎回收费用", "其他费用", "木材费", "预付无线网络费", "道路改造及送餐费", "eBay 收取的费用", "总价",
            "总额包含 eBay 收取的税费和费用", "支付方式", "出售日期", "付款日期", "发货截止日期", "预计最早送达日期",
            "预计最晚送达日期", "发货日期", "留下的信用评价", "收到的信用评价", "我的物品备注", "PayPal 交易编号",
            "运送服务", "追踪号码", "交易编号", "款式详情", "全球运送方案", "全球运送参考编号", "Click & Collect",
            "Click & Collect 参考编号", "eBay Plus", "正品验证计划", "正品验证状态", "正品验证结果理由",
            "PSA 保险箱方案", "eBay 保险箱履单类型", "eBay 履单服务方案", "课税县/市", "课税州/省", "课税邮政编码",
            "课税国家/地区", "eBay 跨国运送");

    public static final String COL_STYLE_ID = "货号";
    public static final String COL_US_SIZE = "us尺码";
    public static final String COL_EU_SIZE = "eu码";
    public static final String COL_ORDER_DATE = "订单日期";
    public static final String COL_SHIP_BY = "发货截止日期(北京时间)";

    public static final List<String> EXTRA_COLUMNS = List.of(
            COL_STYLE_ID, COL_US_SIZE, COL_EU_SIZE, COL_ORDER_DATE, COL_SHIP_BY);

    public static List<String> allColumns() {
        List<String> columns = new ArrayList<>(REPORT_COLUMNS);
        columns.addAll(EXTRA_COLUMNS);
        return columns;
    }

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter REPORT_DATE =
            DateTimeFormatter.ofPattern("MMM-dd-yy", Locale.US).withZone(PACIFIC);
    private static final DateTimeFormatter BEIJING_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(BEIJING);
    private static final String ZERO = "US $0.00";
    private static final Pattern US_PHONE = Pattern.compile("^\\+1 (\\d{3}) (\\d{3}) (\\d{4})$");
    /** 标题末尾形如 "... IO6259-800[10]"：方括号前紧邻的货号。 */
    private static final Pattern TITLE_STYLE = Pattern.compile("([A-Za-z0-9][A-Za-z0-9-]*)\\s*\\[[^\\]]*\\]\\s*$");
    private static final Map<String, String> AUTHENTICITY_STATUS = Map.of(
            "PASSED", "通过检查", "PENDING", "处理中", "FAILED", "未通过检查");

    /** 单个订单行（一笔 Transaction）。 */
    public record Line(LinkedHashMap<String, String> columns,
                       String orderId, String itemId, String sku, String title,
                       String styleId, String usSize, String euSize,
                       BigDecimal price, Instant createdTime, Instant shipBy,
                       String status, int salesRecordNumber) {
    }

    /** 本地映射：按 SKU 精确匹配，或按 eBay 物品编号找到同一商品组。 */
    public interface MappingLookup {
        EbayListingDO bySku(String sku);

        Collection<EbayListingDO> byListingId(String listingId);
    }

    private final Set<String> feedbackLeftLineItemIds;
    private final Map<String, String> shippingServiceNames;
    private final MappingLookup mappingLookup;

    public EbayOrderReportBuilder(Set<String> feedbackLeftLineItemIds,
                                  Map<String, String> shippingServiceNames,
                                  MappingLookup mappingLookup) {
        this.feedbackLeftLineItemIds = feedbackLeftLineItemIds == null ? Set.of() : feedbackLeftLineItemIds;
        this.shippingServiceNames = shippingServiceNames == null ? Map.of() : shippingServiceNames;
        this.mappingLookup = mappingLookup;
    }

    public List<Line> build(Element order) {
        List<Line> lines = new ArrayList<>();
        Element transactionArray = firstElement(order, "TransactionArray");
        List<Element> transactions = children(transactionArray, "Transaction");
        for (Element transaction : transactions) {
            lines.add(buildLine(order, transaction, transactions.size() == 1));
        }
        return lines;
    }

    private Line buildLine(Element order, Element tx, boolean singleLineOrder) {
        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        for (String column : REPORT_COLUMNS) {
            row.put(column, "");
        }
        String orderId = text(order, "OrderID");
        boolean cancelled = "Cancelled".equalsIgnoreCase(text(order, "OrderStatus"));
        String recordNumber = firstNonBlank(text(tx, "ShippingDetails/SellingManagerSalesRecordNumber"),
                text(order, "ShippingDetails/SellingManagerSalesRecordNumber"));
        row.put("销售记录编号", nz(recordNumber));
        row.put("订单编号", nz(orderId));
        row.put("买家用户名", nz(text(order, "BuyerUserID")));
        row.put("买家姓名", joinName(text(tx, "Buyer/UserFirstName"), text(tx, "Buyer/UserLastName")));
        String email = text(tx, "Buyer/Email");
        row.put("买家电子邮件", email != null && email.contains("@") ? email : "");
        row.put("买家备注", nz(text(order, "BuyerCheckoutMessage")));

        Element address = firstElement(order, "ShippingAddress");
        row.put("收货人姓名", nz(text(address, "Name")));
        row.put("收货人电话", formatPhone(text(address, "Phone")));
        row.put("收货地址 1", nz(text(address, "Street1")));
        row.put("收货地址 2", nz(text(address, "Street2")));
        row.put("收货人所在县/市", nz(text(address, "CityName")));
        row.put("收货人所在州/省", nz(text(address, "StateOrProvince")));
        row.put("收货人邮政编码", nz(text(address, "PostalCode")));
        row.put("收货人所在国家/地区", nz(text(address, "CountryName")));

        String itemId = text(tx, "Item/ItemID");
        String title = firstNonBlank(text(tx, "Variation/VariationTitle"), text(tx, "Item/Title"));
        String sku = firstNonBlank(text(tx, "Variation/SKU"), null);
        row.put("物品编号", nz(itemId));
        row.put("物品标题", nz(title));
        row.put("自定义标签", nz(sku));
        String quantity = nz(text(tx, "QuantityPurchased"));
        row.put("数量", quantity);
        BigDecimal price = decimal(text(tx, "TransactionPrice"));
        BigDecimal shipping = decimal(text(tx, "ActualShippingCost"));
        row.put("成交价", money(price));
        row.put("运费与处理费", money(shipping));
        row.put("物品所在地", nz(text(tx, "Item/Location")));
        row.put("物品所在国家/地区", itemCountry(text(tx, "Item/Country"), text(tx, "Item/Location")));

        boolean collectAndRemit = "true".equalsIgnoreCase(text(tx, "eBayCollectAndRemitTax"));
        BigDecimal salesTax = salesTax(tx);
        if (collectAndRemit) {
            BigDecimal rate = decimal(text(order, "ShippingDetails/TaxTable/TaxJurisdiction/SalesTaxPercent"));
            row.put("eBay 收缴税率", rate == null ? "" : rate.setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.put("eBay 收缴税费类型", hasSalesTaxDetail(tx) ? "销售税" : "");
        }
        row.put("卖家收取的税费", ZERO);
        row.put("eBay 收取的税费", money(salesTax == null ? BigDecimal.ZERO : salesTax));
        for (String fee : List.of("电子垃圾回收费用", "床垫回收费用", "电池回收费用", "大型家电处置税", "轮胎回收费用",
                "其他费用", "木材费", "预付无线网络费", "道路改造及送餐费", "eBay 收取的费用")) {
            row.put(fee, ZERO);
        }
        row.put("总价", money(total(order, singleLineOrder, cancelled, price, quantity, shipping, salesTax)));
        row.put("总额包含 eBay 收取的税费和费用", cancelled ? "否" : "是");

        Instant created = instant(firstNonBlank(text(order, "CreatedTime"), text(tx, "CreatedDate")));
        Instant paid = instant(text(order, "PaidTime"));
        Instant shipBy = instant(text(tx, "ShippingServiceSelected/ShippingPackageInfo/HandleByTime"));
        Instant shipped = instant(firstNonBlank(text(tx, "ShippedTime"), text(order, "ShippedTime")));
        row.put("出售日期", reportDate(created));
        row.put("付款日期", reportDate(paid));
        row.put("发货截止日期", reportDate(shipBy));
        row.put("预计最早送达日期", reportDate(instant(text(tx,
                "ShippingServiceSelected/ShippingPackageInfo/EstimatedDeliveryTimeMin"))));
        row.put("预计最晚送达日期", reportDate(instant(text(tx,
                "ShippingServiceSelected/ShippingPackageInfo/EstimatedDeliveryTimeMax"))));
        row.put("发货日期", reportDate(shipped));

        String lineItemId = firstNonBlank(text(tx, "OrderLineItemID"),
                itemId == null ? null : itemId + "-" + text(tx, "TransactionID"));
        row.put("留下的信用评价", feedbackLeftLineItemIds.contains(lineItemId) ? "是" : "否");
        String shippingService = text(order, "ShippingServiceSelected/ShippingService");
        row.put("运送服务", shippingService == null ? ""
                : shippingServiceNames.getOrDefault(shippingService, shippingService));
        Element trackingDetails = firstElement(tx, "ShippingDetails/ShipmentTrackingDetails");
        row.put("追踪号码", nz(text(trackingDetails, "ShipmentTrackingNumber")));
        row.put("交易编号", nz(text(tx, "TransactionID")));
        String usSize = null;
        StringBuilder variation = new StringBuilder();
        for (Element nameValue : children(firstElement(tx, "Variation/VariationSpecifics"), "NameValueList")) {
            String name = text(nameValue, "Name");
            String value = text(nameValue, "Value");
            if (name == null || value == null) {
                continue;
            }
            variation.append(variation.isEmpty() ? "" : ",").append(name).append(':').append(value);
            if (usSize == null && name.toLowerCase(Locale.ROOT).contains("size")) {
                usSize = value;
            }
        }
        row.put("款式详情", variation.isEmpty() ? "" : "[" + variation + "]");
        row.put("全球运送方案", "否");
        row.put("Click & Collect", "否");
        row.put("eBay Plus", "true".equalsIgnoreCase(text(tx, "eBayPlusTransaction")) ? "是" : "否");
        String authenticity = text(tx, "Program/AuthenticityVerification/Status");
        row.put("正品验证计划", authenticity == null ? "否" : "是");
        row.put("正品验证状态", authenticity == null ? "" : AUTHENTICITY_STATUS.getOrDefault(authenticity, authenticity));
        row.put("PSA 保险箱方案", "否");
        row.put("eBay 履单服务方案", "否");
        row.put("eBay 跨国运送", "否");

        Resolved resolved = resolveProduct(sku, itemId, title, usSize);
        row.put(COL_STYLE_ID, nz(resolved.styleId()));
        row.put(COL_US_SIZE, nz(usSize));
        row.put(COL_EU_SIZE, nz(resolved.euSize()));
        row.put(COL_ORDER_DATE, created == null ? "" : BEIJING_DATE_TIME.format(created));
        row.put(COL_SHIP_BY, shipBy == null ? "" : BEIJING_DATE_TIME.format(shipBy));

        return new Line(row, orderId, itemId, sku, title, resolved.styleId(), usSize, resolved.euSize(),
                price, created, shipBy, status(order, tx, shipped, cancelled), parseInt(recordNumber));
    }

    private record Resolved(String styleId, String euSize) {
    }

    /**
     * 货号/EU码：优先按 SKU 精确匹配本地上架映射；没有 SKU（例如手工刊登）时，
     * 用同一物品编号下的映射取货号与品牌，再按尺码表把 US 码换算成 EU 码；最后从标题兜底取货号。
     */
    private Resolved resolveProduct(String sku, String itemId, String title, String usSize) {
        if (mappingLookup != null && sku != null) {
            EbayListingDO mapping = mappingLookup.bySku(sku);
            if (mapping != null && mapping.getStyleId() != null) {
                return new Resolved(mapping.getStyleId(), firstNonBlank(mapping.getEuSize(),
                        euFromUs(mapping.getBrand(), title, usSize)));
            }
        }
        if (mappingLookup != null && itemId != null) {
            Collection<EbayListingDO> group = mappingLookup.byListingId(itemId);
            if (group != null && !group.isEmpty()) {
                EbayListingDO any = group.iterator().next();
                return new Resolved(any.getStyleId(), euFromUs(any.getBrand(), title, usSize));
            }
        }
        return new Resolved(styleFromTitle(title), null);
    }

    private String euFromUs(String brand, String title, String usSize) {
        if (brand == null || usSize == null) {
            return null;
        }
        boolean womens = title != null && title.trim().toUpperCase(Locale.ROOT).startsWith("(WMNS)");
        try {
            return SizeConvertUtil.getKcEuSizeFromUs(brand, womens ? "USW" : "USM", usSize);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String itemCountry(String country, String location) {
        if (country != null) {
            return country;
        }
        if (location == null) {
            return "";
        }
        String normalized = location.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("hong kong") ? "HK"
                : normalized.contains("china") ? "CN"
                : normalized.contains("united states") ? "US" : "";
    }

    static String styleFromTitle(String title) {
        if (title == null) {
            return null;
        }
        Matcher matcher = TITLE_STYLE.matcher(title);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String status(Element order, Element tx, Instant shipped, boolean cancelled) {
        if (cancelled) {
            return "已取消";
        }
        String cancelStatus = text(order, "CancelStatus");
        String returnStatus = text(tx, "Status/ReturnStatus");
        String base;
        if (cancelStatus != null && cancelStatus.toLowerCase(Locale.ROOT).contains("cancelrequested")) {
            base = "买家申请取消";
        } else if (shipped != null) {
            base = "已发货";
        } else {
            base = "待发货";
        }
        if (returnStatus != null && returnStatus.startsWith("Return") && !"ReturnClosed".equals(returnStatus)) {
            base += "(退货中)";
        }
        return base;
    }

    private BigDecimal total(Element order, boolean singleLineOrder, boolean cancelled, BigDecimal price,
                             String quantity, BigDecimal shipping, BigDecimal salesTax) {
        if (cancelled) {
            return BigDecimal.ZERO;
        }
        if (singleLineOrder) {
            BigDecimal total = decimal(text(order, "Total"));
            if (total != null) {
                return total;
            }
        }
        BigDecimal qty = decimal(quantity);
        BigDecimal sum = price == null ? BigDecimal.ZERO : price.multiply(qty == null ? BigDecimal.ONE : qty);
        if (shipping != null) {
            sum = sum.add(shipping);
        }
        if (salesTax != null) {
            sum = sum.add(salesTax);
        }
        return sum;
    }

    /** eBay 代收的销售税：只计 Imposition=SalesTax 的明细，与报告一致。 */
    private BigDecimal salesTax(Element tx) {
        Element taxes = firstElement(tx, "eBayCollectAndRemitTaxes");
        if (taxes == null) {
            return null;
        }
        BigDecimal sum = null;
        for (Element detail : children(taxes, "TaxDetails")) {
            if (!"SalesTax".equalsIgnoreCase(text(detail, "Imposition"))) {
                continue;
            }
            BigDecimal amount = decimal(text(detail, "TaxAmount"));
            if (amount != null) {
                sum = sum == null ? amount : sum.add(amount);
            }
        }
        return sum != null ? sum : decimal(text(taxes, "TotalTaxAmount"));
    }

    private boolean hasSalesTaxDetail(Element tx) {
        for (Element detail : children(firstElement(tx, "eBayCollectAndRemitTaxes"), "TaxDetails")) {
            if ("SalesTax".equalsIgnoreCase(text(detail, "Imposition"))) {
                return true;
            }
        }
        return false;
    }

    private static String formatPhone(String phone) {
        if (phone == null) {
            return "";
        }
        String trimmed = phone.trim();
        Matcher matcher = US_PHONE.matcher(trimmed);
        if (matcher.matches()) {
            return "+1 " + matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
        }
        if (trimmed.matches("\\d{10}")) {
            return "+1 " + trimmed.substring(0, 3) + "-" + trimmed.substring(3, 6) + "-" + trimmed.substring(6);
        }
        return trimmed;
    }

    private static String joinName(String first, String last) {
        return (nz(first) + " " + nz(last)).trim();
    }

    private static String reportDate(Instant instant) {
        return instant == null ? "" : REPORT_DATE.format(instant);
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : "US $" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
