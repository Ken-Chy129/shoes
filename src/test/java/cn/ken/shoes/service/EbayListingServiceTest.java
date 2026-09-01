package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbayPictureApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayInventoryLocationRequest;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayListingServiceTest {

    private EbaySellApiClient apiClient;
    private EbayPictureApiClient pictureApiClient;
    private EbayListingService service;

    @BeforeEach
    void setUp() {
        apiClient = mock(EbaySellApiClient.class);
        pictureApiClient = mock(EbayPictureApiClient.class);
        when(pictureApiClient.uploadExternalPicture(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("https://i.ebayimg.com/images/g/test/s-l1600.jpg");
        EbayProperties properties = new EbayProperties();
        properties.setEnvironment("sandbox");
        service = new EbayListingService(
                apiClient, properties, new EbayPictureService(pictureApiClient));
        when(apiClient.getOffersBySku(anyString())).thenReturn(List.of());
        when(apiClient.getInventoryItemGroup(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void publishesSingleSkuThroughInventoryOfferAndPublishSteps() {
        EbayListingRequest request = listingRequest();
        when(apiClient.createOffer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-123");
        when(apiClient.publishOffer("offer-123")).thenReturn("listing-456");

        EbayListingResult result = service.publish(request);

        InOrder order = inOrder(apiClient);
        ArgumentCaptor<JSONObject> inventoryPayload = ArgumentCaptor.forClass(JSONObject.class);
        order.verify(apiClient).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-1"), inventoryPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        ArgumentCaptor<JSONObject> offerPayload = ArgumentCaptor.forClass(JSONObject.class);
        order.verify(apiClient).createOffer(offerPayload.capture(), org.mockito.ArgumentMatchers.eq("en-US"));
        order.verify(apiClient).publishOffer("offer-123");

        JSONObject inventory = inventoryPayload.getValue();
        assertThat(inventory.getString("condition")).isEqualTo("NEW");
        assertThat(inventory.getJSONObject("availability")
                .getJSONObject("shipToLocationAvailability").getIntValue("quantity")).isEqualTo(2);
        JSONObject product = inventory.getJSONObject("product");
        assertThat(product.getString("title")).isEqualTo("Test Sneaker");
        assertThat(product.getJSONArray("imageUrls")).containsExactly(
                "https://i.ebayimg.com/images/g/test/s-l1600.jpg");
        assertThat(product.getJSONObject("aspects").getJSONArray("US Shoe Size")).containsExactly("9");
        assertThat(product.getJSONObject("aspects").getJSONArray("Brand")).containsExactly("Test Brand");

        JSONObject offer = offerPayload.getValue();
        assertThat(offer.getString("format")).isEqualTo("FIXED_PRICE");
        assertThat(offer.getString("listingDuration")).isEqualTo("GTC");
        assertThat(offer.getJSONObject("pricingSummary").getJSONObject("price"))
                .containsEntry("currency", "USD")
                .containsEntry("value", "129.99");
        assertThat(offer.getJSONObject("listingPolicies"))
                .containsEntry("fulfillmentPolicyId", "fulfillment-1")
                .containsEntry("paymentPolicyId", "payment-1")
                .containsEntry("returnPolicyId", "return-1");
        assertThat(result.getSku()).isEqualTo("shoe-sku-1");
        assertThat(result.getOfferId()).isEqualTo("offer-123");
        assertThat(result.getListingId()).isEqualTo("listing-456");
        assertThat(result.getEnvironment()).isEqualTo("sandbox");
    }

    @Test
    void updatesAnAlreadyPublishedSingleSkuWithoutCreatingOrPublishingAnotherOffer() {
        EbayListingRequest request = listingRequest();
        when(apiClient.getOffersBySku("shoe-sku-1"))
                .thenReturn(List.of(
                        unpublishedOffer("offer-stale", "shoe-sku-1"),
                        publishedOffer(
                                "offer-existing", "shoe-sku-1", "listing-existing")));

        EbayListingResult result = service.publish(request);

        verify(apiClient).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-1"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient).updateOffer(
                org.mockito.ArgumentMatchers.eq("offer-existing"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient, never()).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(apiClient, never()).publishOffer(org.mockito.ArgumentMatchers.anyString());
        assertThat(result.getOfferId()).isEqualTo("offer-existing");
        assertThat(result.getListingId()).isEqualTo("listing-existing");
    }

    @Test
    void reusesAndPublishesAnExistingUnpublishedOffer() {
        EbayListingRequest request = listingRequest();
        when(apiClient.getOffersBySku("shoe-sku-1"))
                .thenReturn(List.of(unpublishedOffer(
                        "offer-unpublished", "shoe-sku-1")));
        when(apiClient.publishOffer("offer-unpublished"))
                .thenReturn("listing-newly-published");

        EbayListingResult result = service.publish(request);

        verify(apiClient).updateOffer(
                org.mockito.ArgumentMatchers.eq("offer-unpublished"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient).publishOffer("offer-unpublished");
        verify(apiClient, never()).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        assertThat(result.getOfferId()).isEqualTo("offer-unpublished");
        assertThat(result.getListingId()).isEqualTo("listing-newly-published");
    }

    @Test
    void publishesMultipleSizesAsOneListingWithIndependentOffers() {
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new java.util.LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-10");
        size10.setQuantity(3);
        size10.setPrice(new BigDecimal("139.99"));
        size10.setAspects(new java.util.LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        when(apiClient.createOffer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-9", "offer-10");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenReturn("listing-group-456");

        List<EbayListingResult> results = service.publishGroup(
                "group-style-1", List.of(size9, size10));

        InOrder order = inOrder(apiClient);
        order.verify(apiClient).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-9"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        order.verify(apiClient).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-10"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        order.verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        ArgumentCaptor<JSONObject> offers = ArgumentCaptor.forClass(JSONObject.class);
        order.verify(apiClient, times(2)).createOffer(
                offers.capture(), org.mockito.ArgumentMatchers.eq("en-US"));
        order.verify(apiClient).publishOfferByInventoryItemGroup("group-style-1", "EBAY_US");

        JSONObject group = groupPayload.getValue();
        assertThat(group.getJSONArray("variantSKUs"))
                .containsExactly("shoe-sku-9", "shoe-sku-10");
        assertThat(group.getJSONObject("aspects"))
                .containsEntry("Brand", List.of("Test Brand"))
                .doesNotContainKey("US Shoe Size");
        JSONObject sizeSpecification = group.getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0);
        assertThat(sizeSpecification.getString("name")).isEqualTo("US Shoe Size");
        assertThat(sizeSpecification.getJSONArray("values")).containsExactly("9", "10");
        assertThat(offers.getAllValues())
                .extracting(offer -> offer.getString("sku"))
                .containsExactly("shoe-sku-9", "shoe-sku-10");
        assertThat(results)
                .extracting(EbayListingResult::getOfferId)
                .containsExactly("offer-9", "offer-10");
        assertThat(results)
                .extracting(EbayListingResult::getListingId)
                .containsOnly("listing-group-456");
        verify(pictureApiClient).uploadExternalPicture(
                "https://example.com/shoe.jpg", "group-style-1-1");
    }

    @Test
    void addsOneNewSizeToAnExistingPublishedGroupWithoutReplacingExistingVariants() {
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-10");
        size10.setAspects(new LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-9"), List.of("9"))));
        when(apiClient.getOffersBySku("shoe-sku-9"))
                .thenReturn(List.of(publishedOffer(
                        "offer-9", "shoe-sku-9", "listing-group-456")));
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-10");
        when(apiClient.publishOffer("offer-10")).thenReturn("listing-group-456");

        List<EbayListingResult> results = service.publishGroup(
                "group-style-1", List.of(size10));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs"))
                .containsExactly("shoe-sku-9", "shoe-sku-10");
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("9", "10");
        verify(apiClient).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient).publishOffer("offer-10");
        verify(apiClient, never()).publishOfferByInventoryItemGroup(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getOfferId()).isEqualTo("offer-10");
            assertThat(result.getListingId()).isEqualTo("listing-group-456");
        });
    }

    @Test
    void updatesPublishedGroupOffersWithoutCreatingOrPublishingDuplicates() {
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-10");
        size10.setAspects(new LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-9", "shoe-sku-10"), List.of("9", "10"))));
        when(apiClient.getOffersBySku("shoe-sku-9"))
                .thenReturn(List.of(publishedOffer(
                        "offer-9", "shoe-sku-9", "listing-group-456")));
        when(apiClient.getOffersBySku("shoe-sku-10"))
                .thenReturn(List.of(publishedOffer(
                        "offer-10", "shoe-sku-10", "listing-group-456")));

        List<EbayListingResult> results = service.publishGroup(
                "group-style-1", List.of(size9, size10));

        verify(apiClient, times(2)).updateOffer(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient, never()).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(apiClient, never()).publishOffer(org.mockito.ArgumentMatchers.anyString());
        verify(apiClient, never()).publishOfferByInventoryItemGroup(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThat(results)
                .extracting(EbayListingResult::getOfferId)
                .containsExactly("offer-9", "offer-10");
        assertThat(results)
                .extracting(EbayListingResult::getListingId)
                .containsOnly("listing-group-456");
    }

    @Test
    void usesUsSizeAsTheSingleVariationWhenEuAndUsAspectsAreBothPresent() {
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-eu-9");
        size9.setAspects(new java.util.LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        size9.getAspects().put("EU Shoe Size", List.of("42.5"));
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-eu-10");
        size10.setAspects(new java.util.LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        size10.getAspects().put("EU Shoe Size", List.of("44"));
        when(apiClient.createOffer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-eu-9", "offer-eu-10");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-eu", "EBAY_US"))
                .thenReturn("listing-group-eu");

        service.publishGroup("group-style-eu", List.of(size9, size10));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-eu"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        JSONObject specification = groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0);
        assertThat(specification.getString("name")).isEqualTo("US Shoe Size");
        assertThat(groupPayload.getValue().getJSONObject("aspects")).doesNotContainKey("EU Shoe Size");
    }

    @Test
    void rejectsNonHttpImageUrlBeforeCallingEbay() {
        EbayListingRequest request = listingRequest();
        request.setImageUrls(List.of("file:///etc/passwd"));

        assertThatThrownBy(() -> service.publish(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image URL");
        verify(apiClient, never()).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aggregatesLocationsAndPoliciesWithoutSecrets() {
        when(apiClient.getInventoryLocations()).thenReturn(new JSONObject().fluentPut("locations", List.of()));
        when(apiClient.getFulfillmentPolicies("EBAY_US"))
                .thenReturn(new JSONObject().fluentPut("fulfillmentPolicies", List.of(Map.of("id", "f-1"))));
        when(apiClient.getPaymentPolicies("EBAY_US"))
                .thenReturn(new JSONObject().fluentPut("paymentPolicies", List.of(Map.of("id", "p-1"))));
        when(apiClient.getReturnPolicies("EBAY_US"))
                .thenReturn(new JSONObject().fluentPut("returnPolicies", List.of(Map.of("id", "r-1"))));

        JSONObject result = service.getPrerequisites("EBAY_US");

        assertThat(result.getString("environment")).isEqualTo("sandbox");
        assertThat(result.getJSONObject("locations")).isNotNull();
        assertThat(result.getJSONObject("fulfillmentPolicies")).isNotNull();
        assertThat(result.toJSONString()).doesNotContain("access_token", "refresh_token", "clientSecret");
    }

    @Test
    void createsEnabledWarehouseLocationFromValidatedAddress() {
        EbayInventoryLocationRequest request = new EbayInventoryLocationRequest();
        request.setMerchantLocationKey("shenzhen-main");
        request.setName("Shenzhen Warehouse");
        request.setAddressLine1("南山街道");
        request.setAddressLine2("1栋101室");
        request.setCity("深圳");
        request.setStateOrProvince("广东");
        request.setPostalCode("518000");
        request.setCountry("CN");

        service.createInventoryLocation(request);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createInventoryLocation(
                org.mockito.ArgumentMatchers.eq("shenzhen-main"), payload.capture());
        JSONObject body = payload.getValue();
        assertThat(body.getString("merchantLocationStatus")).isEqualTo("ENABLED");
        assertThat(body.getJSONArray("locationTypes")).containsExactly("WAREHOUSE");
        assertThat(body.getJSONObject("location").getJSONObject("address"))
                .containsEntry("country", "CN")
                .containsEntry("postalCode", "518000");
    }

    @Test
    void omitsPostalCodeForHongKongWarehouseLocation() {
        EbayInventoryLocationRequest request = new EbayInventoryLocationRequest();
        request.setMerchantLocationKey("hong_kong_mong_kok");
        request.setName("Hong Kong Warehouse");
        request.setAddressLine1("Room 2, 2/F, Dezan Centre, 80 Larch Street");
        request.setAddressLine2("Tai Kok Tsui, Mong Kok");
        request.setCity("Hong Kong");
        request.setStateOrProvince("Hong Kong");
        request.setCountry("HK");

        service.createInventoryLocation(request);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createInventoryLocation(
                org.mockito.ArgumentMatchers.eq("hong_kong_mong_kok"), payload.capture());
        assertThat(payload.getValue().getJSONObject("location").getJSONObject("address"))
                .containsEntry("country", "HK")
                .doesNotContainKey("postalCode");
    }

    private EbayListingRequest listingRequest() {
        EbayListingRequest request = new EbayListingRequest();
        request.setSku("shoe-sku-1");
        request.setTitle("Test Sneaker");
        request.setDescription("Brand new test sneaker");
        request.setImageUrls(List.of("https://example.com/shoe.jpg"));
        request.setQuantity(2);
        request.setCondition("NEW");
        request.setCategoryId("15709");
        request.setMarketplaceId("EBAY_US");
        request.setCurrency("USD");
        request.setPrice(new BigDecimal("129.99"));
        request.setMerchantLocationKey("shenzhen-main");
        request.setFulfillmentPolicyId("fulfillment-1");
        request.setPaymentPolicyId("payment-1");
        request.setReturnPolicyId("return-1");
        request.setBrand("Test Brand");
        request.setMpn("TEST-1");
        request.setAspects(Map.of("US Shoe Size", List.of("9")));
        return request;
    }

    private JSONObject publishedOffer(String offerId, String sku, String listingId) {
        return new JSONObject(true)
                .fluentPut("offerId", offerId)
                .fluentPut("sku", sku)
                .fluentPut("marketplaceId", "EBAY_US")
                .fluentPut("status", "PUBLISHED")
                .fluentPut("listing", new JSONObject(true).fluentPut("listingId", listingId));
    }

    private JSONObject unpublishedOffer(String offerId, String sku) {
        return new JSONObject(true)
                .fluentPut("offerId", offerId)
                .fluentPut("sku", sku)
                .fluentPut("marketplaceId", "EBAY_US")
                .fluentPut("status", "UNPUBLISHED");
    }

    private JSONObject inventoryGroup(List<String> skus, List<String> sizes) {
        JSONObject specification = new JSONObject(true)
                .fluentPut("name", "US Shoe Size")
                .fluentPut("values", sizes);
        return new JSONObject(true)
                .fluentPut("title", "Test Sneaker")
                .fluentPut("variantSKUs", skus)
                .fluentPut("aspects", new JSONObject(true)
                        .fluentPut("Brand", List.of("Test Brand")))
                .fluentPut("variesBy", new JSONObject(true)
                        .fluentPut("specifications", List.of(specification)));
    }
}
