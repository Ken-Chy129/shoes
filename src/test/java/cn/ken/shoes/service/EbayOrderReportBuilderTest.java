package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.model.entity.EbayListingDO;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EbayOrderReportBuilderTest {

    private static final String ORDER = """
            <GetOrdersResponse xmlns="urn:ebay:apis:eBLBaseComponents"><OrderArray><Order>
              <OrderID>07-15295-85293</OrderID>
              <OrderStatus>Completed</OrderStatus>
              <ShippingDetails>
                <SellingManagerSalesRecordNumber>137</SellingManagerSalesRecordNumber>
                <TaxTable><TaxJurisdiction><SalesTaxPercent>8.875</SalesTaxPercent></TaxJurisdiction></TaxTable>
              </ShippingDetails>
              <CreatedTime>2026-10-13T11:37:39.000Z</CreatedTime>
              <ShippingAddress>
                <Name>SKC3 C/O Jhoan Garcia</Name><Street1>65 Oxford Dr</Street1><Street2>evtn:g2ft2pj</Street2>
                <CityName>Moonachie</CityName><StateOrProvince>NJ</StateOrProvince>
                <CountryName>United States</CountryName><Phone>+1 917 387 7468</Phone><PostalCode>07074-1020</PostalCode>
              </ShippingAddress>
              <ShippingServiceSelected><ShippingService>ExpressInternationalShipping_2</ShippingService></ShippingServiceSelected>
              <Total currencyID="USD">268.93</Total>
              <TransactionArray><Transaction>
                <Buyer><Email>abc@members.ebay.com</Email><UserFirstName>Jhoan</UserFirstName><UserLastName>Garcia</UserLastName></Buyer>
                <Item><ItemID>407260471462</ItemID><Location>Hong Kong</Location>
                  <Title>Jordan 13 Retro OG Chicago (2026) 414571-102[7.5]</Title></Item>
                <QuantityPurchased>1</QuantityPurchased>
                <TransactionID>10089705601807</TransactionID>
                <TransactionPrice currencyID="USD">232.06</TransactionPrice>
                <ShippingServiceSelected><ShippingPackageInfo>
                  <EstimatedDeliveryTimeMin>2026-10-31T07:00:00.000Z</EstimatedDeliveryTimeMin>
                  <EstimatedDeliveryTimeMax>2026-10-31T07:00:00.000Z</EstimatedDeliveryTimeMax>
                  <HandleByTime>2026-10-22T15:59:59.000Z</HandleByTime>
                </ShippingPackageInfo></ShippingServiceSelected>
                <Variation><SKU>EBAY414571102EU405NEWC34C30DE</SKU>
                  <VariationSpecifics><NameValueList><Name>US Shoe Size</Name><Value>7.5</Value></NameValueList></VariationSpecifics>
                  <VariationTitle>Jordan 13 Retro OG Chicago (2026) 414571-102[7.5]</VariationTitle></Variation>
                <ActualShippingCost currencyID="USD">14.95</ActualShippingCost>
                <OrderLineItemID>407260471462-10089705601807</OrderLineItemID>
                <eBayCollectAndRemitTax>true</eBayCollectAndRemitTax>
                <eBayCollectAndRemitTaxes><TotalTaxAmount>22.23</TotalTaxAmount>
                  <TaxDetails><Imposition>SalesTax</Imposition><TaxAmount>21.92</TaxAmount></TaxDetails>
                  <TaxDetails><Imposition>CustomCode</Imposition><TaxAmount>0.31</TaxAmount></TaxDetails>
                </eBayCollectAndRemitTaxes>
                <Program><AuthenticityVerification><Status>PENDING</Status></AuthenticityVerification></Program>
              </Transaction></TransactionArray>
              <BuyerUserID>OLPmPPsRRZ2</BuyerUserID>
              <PaidTime>2026-10-13T11:37:38.981Z</PaidTime>
              <CancelStatus>NotApplicable</CancelStatus>
            </Order></OrderArray></GetOrdersResponse>
            """;

    @Test
    void buildsAReportRowMatchingTheSellerHubDownloadPlusExtraColumns() throws Exception {
        EbayListingDO mapping = new EbayListingDO();
        mapping.setSku("EBAY414571102EU405NEWC34C30DE");
        mapping.setStyleId("414571-102");
        mapping.setEuSize("40.5");
        var builder = new EbayOrderReportBuilder(Set.of(),
                Map.of("ExpressInternationalShipping_2", "Express International Shipping"),
                lookup(Map.of(mapping.getSku(), mapping)));

        var lines = builder.build(order(ORDER));

        assertThat(lines).hasSize(1);
        var line = lines.get(0);
        var row = line.columns();
        assertThat(List.copyOf(row.keySet())).isEqualTo(EbayOrderReportBuilder.allColumns());
        assertThat(row.get("销售记录编号")).isEqualTo("137");
        assertThat(row.get("订单编号")).isEqualTo("07-15295-85293");
        assertThat(row.get("买家姓名")).isEqualTo("Jhoan Garcia");
        assertThat(row.get("收货人电话")).isEqualTo("+1 917-387-7468");
        assertThat(row.get("物品标题")).isEqualTo("Jordan 13 Retro OG Chicago (2026) 414571-102[7.5]");
        assertThat(row.get("自定义标签")).isEqualTo("EBAY414571102EU405NEWC34C30DE");
        assertThat(row.get("成交价")).isEqualTo("US $232.06");
        assertThat(row.get("运费与处理费")).isEqualTo("US $14.95");
        assertThat(row.get("物品所在国家/地区")).isEqualTo("HK");
        assertThat(row.get("eBay 收缴税率")).isEqualTo("8.88");
        assertThat(row.get("eBay 收缴税费类型")).isEqualTo("销售税");
        assertThat(row.get("eBay 收取的税费")).isEqualTo("US $21.92");
        assertThat(row.get("总价")).isEqualTo("US $268.93");
        assertThat(row.get("出售日期")).isEqualTo("Oct-13-26");
        assertThat(row.get("发货截止日期")).isEqualTo("Oct-22-26");
        assertThat(row.get("预计最早送达日期")).isEqualTo("Oct-31-26");
        assertThat(row.get("留下的信用评价")).isEqualTo("否");
        assertThat(row.get("运送服务")).isEqualTo("Express International Shipping");
        assertThat(row.get("款式详情")).isEqualTo("[US Shoe Size:7.5]");
        assertThat(row.get("正品验证状态")).isEqualTo("处理中");
        assertThat(row.get("货号")).isEqualTo("414571-102");
        assertThat(row.get("us尺码")).isEqualTo("7.5");
        assertThat(row.get("eu码")).isEqualTo("40.5");
        assertThat(row.get("订单日期")).isEqualTo("2026-10-13 19:37");
        assertThat(row.get("发货截止日期(北京时间)")).isEqualTo("2026-10-22 23:59");
        assertThat(line.status()).isEqualTo("待发货");
    }

    @Test
    void fallsBackToTheTitleForTheStyleIdWhenNoMappingExists() throws Exception {
        String unmapped = ORDER.replace("<SKU>EBAY414571102EU405NEWC34C30DE</SKU>", "")
                .replace("414571-102[7.5]</VariationTitle>", "IO6259-800[10]</VariationTitle>");
        var builder = new EbayOrderReportBuilder(Set.of("407260471462-10089705601807"), Map.of(), lookup(Map.of()));

        var row = builder.build(order(unmapped)).get(0).columns();

        assertThat(row.get("自定义标签")).isEmpty();
        assertThat(row.get("货号")).isEqualTo("IO6259-800");
        assertThat(row.get("留下的信用评价")).isEqualTo("是");
        assertThat(row.get("运送服务")).isEqualTo("ExpressInternationalShipping_2");
    }

    @Test
    void reportsCancelledOrdersWithAZeroTotal() throws Exception {
        String cancelled = ORDER.replace("<OrderStatus>Completed</OrderStatus>", "<OrderStatus>Cancelled</OrderStatus>");
        var line = new EbayOrderReportBuilder(Set.of(), Map.of(), lookup(Map.of())).build(order(cancelled)).get(0);

        assertThat(line.columns().get("总价")).isEqualTo("US $0.00");
        assertThat(line.columns().get("总额包含 eBay 收取的税费和费用")).isEqualTo("是");
        assertThat(line.status()).isEqualTo("已取消");
    }

    @Test
    void fillsBuyerAndTaxAddressesPromotionTotalAndShippedDateFromTheFulfillmentApi() throws Exception {
        var ffOrder = JSON.parseObject("""
                {"orderId":"07-15295-85293","orderPaymentStatus":"PAID",
                 "cancelStatus":{"cancelState":"NONE_REQUESTED"},
                 "buyer":{"taxAddress":{"city":"Brooklyn","stateOrProvince":"NY","postalCode":"11235-4227","countryCode":"US"},
                   "buyerRegistrationAddress":{"fullName":"Jhoan Garcia","contactAddress":{"addressLine1":"1230 Avenue X",
                     "city":"Brooklyn","stateOrProvince":"NY","postalCode":"11235","countryCode":"US"}}},
                 "lineItems":[{"lineItemId":"10089705601807","total":{"value":"247.01"},
                   "ebayCollectAndRemitTaxes":[{"amount":{"value":"21.92"}}],
                   "properties":{"buyerProtection":true,"soldViaAdCampaign":true}}]}
                """);
        var enrichment = new EbayOrderReportBuilder.Enrichment(Map.of("07-15295-85293", ffOrder),
                Map.of("10089705601807", Instant.parse("2026-10-14T02:00:00Z")));
        var line = new EbayOrderReportBuilder(Set.of(), Map.of(), lookup(Map.of()), enrichment)
                .build(order(ORDER)).get(0);
        var row = line.columns();

        assertThat(row.get("买家地址 1")).isEqualTo("1230 Avenue X");
        assertThat(row.get("买家所在县/市")).isEqualTo("Brooklyn");
        assertThat(row.get("买家邮政编码")).isEqualTo("11235-4227");
        assertThat(row.get("买家所在国家/地区")).isEqualTo("United States");
        assertThat(row.get("课税州/省")).isEqualTo("NY");
        assertThat(row.get("课税国家/地区")).isEqualTo("United States");
        assertThat(row.get("通过促销刊登售出")).isEqualTo("是");
        assertThat(row.get("总价")).isEqualTo("US $268.93");
        assertThat(row.get("发货日期")).isEqualTo("Oct-13-26");
        assertThat(line.status()).isEqualTo("已发货");
    }

    @Test
    void leavesTheBuyerStreetBlankWhenTheRegistrationAddressIsElsewhereAndZeroesRefundedOrders() throws Exception {
        var ffOrder = JSON.parseObject("""
                {"orderId":"07-15295-85293","orderPaymentStatus":"FULLY_REFUNDED",
                 "cancelStatus":{"cancelState":"NONE_REQUESTED"},
                 "buyer":{"taxAddress":{"stateOrProvince":"MT","postalCode":"59808-9190","countryCode":"US"},
                   "buyerRegistrationAddress":{"contactAddress":{"addressLine1":"9 Other St","city":"Missoula",
                     "stateOrProvince":"MT","postalCode":"59801-8548","countryCode":"US"}}},
                 "lineItems":[{"lineItemId":"10089705601807","total":{"value":"247.01"},"properties":{}}]}
                """);
        var enrichment = new EbayOrderReportBuilder.Enrichment(Map.of("07-15295-85293", ffOrder), Map.of());
        var row = new EbayOrderReportBuilder(Set.of(), Map.of(), lookup(Map.of()), enrichment)
                .build(order(ORDER)).get(0).columns();

        assertThat(row.get("买家地址 1")).isEmpty();
        assertThat(row.get("买家所在县/市")).isEqualTo("Missoula");
        assertThat(row.get("通过促销刊登售出")).isEqualTo("否");
        assertThat(row.get("总价")).isEqualTo("US $0.00");
    }

    @Test
    void extractsStyleIdsFromListingTitles() {
        assertThat(EbayOrderReportBuilder.styleFromTitle("Nike Kobe 8 Protro  Shanghai Fireworks (2026)IO6259-800[10]"))
                .isEqualTo("IO6259-800");
        assertThat(EbayOrderReportBuilder.styleFromTitle("adidas Superstar White Black EG4958[10.5]"))
                .isEqualTo("EG4958");
        assertThat(EbayOrderReportBuilder.styleFromTitle("no size here")).isNull();
    }

    private static EbayOrderReportBuilder.MappingLookup lookup(Map<String, EbayListingDO> bySku) {
        return new EbayOrderReportBuilder.MappingLookup() {
            @Override
            public EbayListingDO bySku(String sku) {
                return bySku.get(sku);
            }

            @Override
            public Collection<EbayListingDO> byListingId(String listingId) {
                return List.of();
            }
        };
    }

    private static Element order(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return EbayTradingApiClient.children(
                EbayTradingApiClient.firstElement(document.getDocumentElement(), "OrderArray"), "Order").get(0);
    }
}
