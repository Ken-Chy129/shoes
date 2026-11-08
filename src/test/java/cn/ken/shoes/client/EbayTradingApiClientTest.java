package cn.ken.shoes.client;

import cn.ken.shoes.service.EbayOAuthService;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayTradingApiClientTest {

    private MockWebServer server;
    private EbayTradingApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        EbayOAuthService oauthService = mock(EbayOAuthService.class);
        when(oauthService.getValidAccessToken()).thenReturn("seller-access-token");
        client = new EbayTradingApiClient(oauthService, new OkHttpClient(), server.url("/ws/api.dll").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void requestsOnePageOfSellerOrders() throws Exception {
        server.enqueue(xml("""
                <GetOrdersResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>2</TotalNumberOfPages><TotalNumberOfEntries>101</TotalNumberOfEntries></PaginationResult>
                  <HasMoreOrders>true</HasMoreOrders>
                  <OrderArray><Order><OrderID>1</OrderID></Order><Order><OrderID>2</OrderID></Order></OrderArray>
                </GetOrdersResponse>
                """));

        var page = client.getOrders(Instant.parse("2026-07-16T00:00:00.123Z"),
                Instant.parse("2026-10-14T00:00:00Z"), 1);

        assertThat(page.orders()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.totalPages()).isEqualTo(2);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-EBAY-API-CALL-NAME")).isEqualTo("GetOrders");
        assertThat(request.getHeader("X-EBAY-API-IAF-TOKEN")).isEqualTo("seller-access-token");
        assertThat(request.getBody().readUtf8())
                .contains("<CreateTimeFrom>2026-07-16T00:00:00Z</CreateTimeFrom>")
                .contains("<OrderStatus>All</OrderStatus>")
                .contains("<OrderRole>Seller</OrderRole>")
                .contains("<PageNumber>1</PageNumber>");
    }

    @Test
    void collectsLineItemsThatAlreadyHaveFeedbackLeft() {
        server.enqueue(xml("""
                <GetFeedbackResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <FeedbackDetailArray>
                    <FeedbackDetail><OrderLineItemID>1-2</OrderLineItemID></FeedbackDetail>
                    <FeedbackDetail><ItemID>3</ItemID><TransactionID>4</TransactionID></FeedbackDetail>
                  </FeedbackDetailArray>
                </GetFeedbackResponse>
                """));

        assertThat(client.getFeedbackLeftLineItemIds()).containsExactlyInAnyOrder("1-2", "3-4");
    }

    @Test
    void surfacesTradingApiErrors() {
        server.enqueue(xml("""
                <GetOrdersResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Failure</Ack>
                  <Errors><ShortMessage>Invalid date range.</ShortMessage><ErrorCode>21843</ErrorCode>
                    <SeverityCode>Error</SeverityCode></Errors>
                </GetOrdersResponse>
                """));

        assertThatThrownBy(() -> client.getOrders(Instant.now(), Instant.now(), 1))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("21843");
    }

    @Test
    void looksUpOrdersByIdAndEscapesThem() throws Exception {
        server.enqueue(xml("""
                <GetOrdersResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <OrderArray><Order><OrderID>21-15259-42163</OrderID></Order></OrderArray>
                </GetOrdersResponse>
                """));

        var orders = client.getOrdersByIds(java.util.List.of("21-15259-42163", "a<b"));

        assertThat(orders).hasSize(1);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-EBAY-API-CALL-NAME")).isEqualTo("GetOrders");
        assertThat(request.getBody().readUtf8())
                .contains("<OrderID>21-15259-42163</OrderID>")
                .contains("<OrderID>a&lt;b</OrderID>")
                .contains("<OrderRole>Seller</OrderRole>")
                .doesNotContain("CreateTimeFrom");
    }

    @Test
    void addsTrackingThroughCompleteSaleAndMarksShipped() throws Exception {
        server.enqueue(xml("""
                <CompleteSaleResponse xmlns="urn:ebay:apis:eBLBaseComponents"><Ack>Success</Ack></CompleteSaleResponse>
                """));

        client.addTracking("21-15259-42163", java.util.List.of(
                new EbayTradingApiClient.ShipmentTracking("524382226900", "FedEx"),
                new EbayTradingApiClient.ShipmentTracking("1Z999", "UPS")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-EBAY-API-CALL-NAME")).isEqualTo("CompleteSale");
        String body = request.getBody().readUtf8();
        assertThat(body)
                .contains("<OrderID>21-15259-42163</OrderID>")
                .contains("<Shipped>true</Shipped>")
                .contains("<ShipmentTrackingNumber>524382226900</ShipmentTrackingNumber>")
                .contains("<ShippingCarrierUsed>FedEx</ShippingCarrierUsed>")
                .contains("<ShipmentTrackingNumber>1Z999</ShipmentTrackingNumber>")
                .contains("<ShippingCarrierUsed>UPS</ShippingCarrierUsed>");
        assertThat(body.indexOf("<Shipment>")).isLessThan(body.indexOf("<ShipmentTrackingDetails>"));
    }

    @Test
    void surfacesCompleteSaleErrors() {
        server.enqueue(xml("""
                <CompleteSaleResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Failure</Ack>
                  <Errors><ShortMessage>Invalid tracking number.</ShortMessage><ErrorCode>21916</ErrorCode>
                    <SeverityCode>Error</SeverityCode></Errors>
                </CompleteSaleResponse>
                """));

        assertThatThrownBy(() -> client.addTracking("1-2-3",
                java.util.List.of(new EbayTradingApiClient.ShipmentTracking("x1234", "FedEx"))))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("21916");
    }

    private static MockResponse xml(String body) {
        return new MockResponse().setHeader("Content-Type", "text/xml").setBody(body);
    }

    @Test
    void readsActiveSkusFromSellerListIncludingVariations() throws Exception {
        server.enqueue(xml("""
                <GetSellerListResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>2</TotalNumberOfPages></PaginationResult>
                  <ItemArray>
                    <Item><ItemID>1</ItemID><SKU>S1</SKU><StartPrice currencyID="USD">150.0</StartPrice>
                      <Quantity>3</Quantity><SellingStatus><QuantitySold>1</QuantitySold></SellingStatus></Item>
                    <Item><ItemID>2</ItemID>
                      <Variations>
                        <Variation><SKU>V1</SKU><StartPrice>99.5</StartPrice><Quantity>1</Quantity>
                          <SellingStatus><QuantitySold>1</QuantitySold></SellingStatus></Variation>
                        <Variation><SKU>V2</SKU><StartPrice>120</StartPrice><Quantity>2</Quantity></Variation>
                      </Variations></Item>
                  </ItemArray>
                </GetSellerListResponse>
                """));
        server.enqueue(xml("""
                <GetSellerListResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>2</TotalNumberOfPages></PaginationResult>
                  <ItemArray><Item><ItemID>3</ItemID><SKU>S3</SKU><StartPrice>80</StartPrice><Quantity>0</Quantity></Item></ItemArray>
                </GetSellerListResponse>
                """));

        var skus = client.getActiveSkus(() -> false);

        assertThat(skus).extracting(EbayTradingApiClient.ActiveSku::sku).containsExactly("S1", "V1", "V2", "S3");
        assertThat(skus.get(0).listingId()).isEqualTo("1");
        assertThat(skus.get(0).price()).isEqualByComparingTo("150");
        assertThat(skus.get(0).availableQuantity()).isEqualTo(2);
        assertThat(skus.get(1).listingId()).isEqualTo("2");
        assertThat(skus.get(1).availableQuantity()).isZero();
        assertThat(skus.get(2).availableQuantity()).isEqualTo(2);
        RecordedRequest first = server.takeRequest();
        assertThat(first.getHeader("X-EBAY-API-CALL-NAME")).isEqualTo("GetSellerList");
        assertThat(first.getBody().readUtf8()).contains("<IncludeVariations>true</IncludeVariations>")
                .contains("<PageNumber>1</PageNumber>");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("<PageNumber>2</PageNumber>");
    }

    @Test
    void handsEachSellerListPageToConsumerBeforeRequestingNext() {
        server.enqueue(xml("""
                <GetSellerListResponse xmlns="urn:ebay:apis:eBLBaseComponents"><Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>2</TotalNumberOfPages></PaginationResult>
                  <ItemArray><Item><ItemID>1</ItemID><SKU>S1</SKU><StartPrice>10</StartPrice><Quantity>1</Quantity></Item></ItemArray>
                </GetSellerListResponse>
                """));
        server.enqueue(xml("""
                <GetSellerListResponse xmlns="urn:ebay:apis:eBLBaseComponents"><Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>2</TotalNumberOfPages></PaginationResult>
                  <ItemArray><Item><ItemID>2</ItemID><SKU>S2</SKU><StartPrice>20</StartPrice><Quantity>1</Quantity></Item></ItemArray>
                </GetSellerListResponse>
                """));
        java.util.List<String> seen = new java.util.ArrayList<>();

        client.forEachActiveSkuPage(() -> false, page -> {
            seen.add(page.pageNumber() + "/" + page.totalPages() + ":" + page.skus().get(0).sku()
                    + " requests=" + server.getRequestCount());
        });

        assertThat(seen).containsExactly("1/2:S1 requests=1", "2/2:S2 requests=2");
    }

    @Test
    void parsesSellerListingsWithSizesWatchersAndRelistLinks() throws Exception {
        server.enqueue(xml("""
                <GetSellerListResponse xmlns="urn:ebay:apis:eBLBaseComponents">
                  <Ack>Success</Ack>
                  <PaginationResult><TotalNumberOfPages>3</TotalNumberOfPages>
                    <TotalNumberOfEntries>450</TotalNumberOfEntries></PaginationResult>
                  <ItemArray>
                    <Item><ItemID>407333465699</ItemID><Title>Nike Air Force 1 CZ1055-100</Title>
                      <SKU>GROUP-1</SKU><TimeLeft>P2DT10H37M34S</TimeLeft><WatchCount>7</WatchCount>
                      <RelistParentID>407100000000</RelistParentID>
                      <ListingDetails><StartTime>2026-11-03T08:00:00.000Z</StartTime>
                        <EndTime>2026-12-03T08:00:00.000Z</EndTime>
                        <ViewItemURL>https://www.ebay.com/itm/407333465699</ViewItemURL></ListingDetails>
                      <SellingStatus><ListingStatus>Active</ListingStatus></SellingStatus>
                      <Variations>
                        <Variation><SKU>V-9</SKU><StartPrice>105.64</StartPrice><Quantity>2</Quantity>
                          <VariationSpecifics><NameValueList><Name>US Shoe Size</Name><Value>9</Value></NameValueList></VariationSpecifics>
                          <SellingStatus><QuantitySold>1</QuantitySold></SellingStatus></Variation>
                        <Variation><SKU>V-10</SKU><StartPrice>120</StartPrice><Quantity>1</Quantity>
                          <VariationSpecifics><NameValueList><Name>US Shoe Size</Name><Value>10</Value></NameValueList></VariationSpecifics>
                        </Variation>
                      </Variations></Item>
                  </ItemArray>
                </GetSellerListResponse>
                """));

        EbayTradingApiClient.SellerListingPage page = client.getSellerListings(
                java.time.Instant.parse("2026-11-09T00:00:00Z"), java.time.Instant.parse("2027-03-08T00:00:00Z"), 1);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-EBAY-API-CALL-NAME")).isEqualTo("GetSellerList");
        assertThat(request.getBody().readUtf8()).contains("<IncludeWatchCount>true</IncludeWatchCount>")
                .contains("<EndTimeFrom>2026-11-09T00:00:00Z</EndTimeFrom>").contains("<PageNumber>1</PageNumber>");
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.totalEntries()).isEqualTo(450);
        EbayTradingApiClient.SellerListing listing = page.listings().get(0);
        assertThat(listing.active()).isTrue();
        assertThat(listing.hasVariations()).isTrue();
        assertThat(listing.watchCount()).isEqualTo(7);
        assertThat(listing.relistParentId()).isEqualTo("407100000000");
        assertThat(listing.timeLeft()).isEqualTo("P2DT10H37M34S");
        assertThat(listing.variations()).extracting(EbayTradingApiClient.ListingVariation::size).containsExactly("9", "10");
        assertThat(listing.variations().get(0).available()).isEqualTo(1);
        assertThat(listing.variations().get(0).price()).isEqualByComparingTo("105.64");
    }

    @Test
    void endItemTreatsAlreadyEndedAsNotEnded() {
        server.enqueue(xml("""
                <EndItemResponse xmlns="urn:ebay:apis:eBLBaseComponents"><Ack>Failure</Ack>
                  <Errors><ShortMessage>The auction has already been closed.</ShortMessage>
                    <ErrorCode>1047</ErrorCode><SeverityCode>Error</SeverityCode></Errors>
                </EndItemResponse>
                """));
        server.enqueue(xml("""
                <EndItemResponse xmlns="urn:ebay:apis:eBLBaseComponents"><Ack>Success</Ack></EndItemResponse>
                """));

        assertThat(client.endItem("1")).isFalse();
        assertThat(client.endItem("2")).isTrue();
    }

    @Test
    void relistReturnsNewItemId() throws Exception {
        server.enqueue(xml("""
                <RelistFixedPriceItemResponse xmlns="urn:ebay:apis:eBLBaseComponents"><Ack>Success</Ack>
                  <ItemID>407999999999</ItemID></RelistFixedPriceItemResponse>
                """));

        assertThat(client.relistFixedPriceItem("407100000000")).isEqualTo("407999999999");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("<ItemID>407100000000</ItemID>");
    }
}
