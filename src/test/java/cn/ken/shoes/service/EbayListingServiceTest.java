package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbayPictureApiClient;
import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayInventoryLocationRequest;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.doThrow;
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
                .thenReturn(Optional.of(
                        "https://i.ebayimg.com/images/g/test/s-l1600.jpg"));
        EbayProperties properties = new EbayProperties();
        properties.setEnvironment("sandbox");
        service = new EbayListingService(
                apiClient, properties, new EbayPictureService(pictureApiClient), ignored -> {
                });
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
    void repairsAvailabilityAndRetriesTransientPublishFailuresWithoutCreatingAnotherOffer() {
        EbayListingRequest request = listingRequest();
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-123");
        when(apiClient.publishOffer("offer-123"))
                .thenThrow(new EbayApiException(
                        "eBay API request failed (HTTP 400): 25604: Availability not found"))
                .thenReturn("listing-456");

        EbayListingResult result = service.publish(request);

        assertThat(result.getListingId()).isEqualTo("listing-456");
        verify(apiClient, times(2)).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-1"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient).updateOffer(
                org.mockito.ArgumentMatchers.eq("offer-123"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient, times(2)).publishOffer("offer-123");
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

    @ParameterizedTest
    @ValueSource(strings = {"US Shoe Size", "US Size", "EU Shoe Size"})
    void sortsNewGroupSizesNumericallyWithoutReorderingResultsOrOffers(String sizeAspect) {
        EbayListingRequest size10 = requestWithSize("sku-a", sizeAspect, "10");
        size10.setQuantity(3);
        size10.setPrice(new BigDecimal("139.99"));
        EbayListingRequest size9 = requestWithSize("sku-c", sizeAspect, "9");
        EbayListingRequest size95 = requestWithSize("sku-b", sizeAspect, "9.5");
        List<EbayListingRequest> requests = List.of(size10, size9, size95);
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-10", "offer-9", "offer-9-5");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenReturn("listing-group-456");

        List<EbayListingResult> results = service.publishGroup("group-style-1", requests);

        assertGroupSizeOrder(List.of("sku-c", "sku-b", "sku-a"), List.of("9", "9.5", "10"));
        assertThat(requests).containsExactly(size10, size9, size95);
        assertThat(results).extracting(EbayListingResult::getSku)
                .containsExactly("sku-a", "sku-c", "sku-b");
        assertThat(results).extracting(EbayListingResult::getOfferId)
                .containsExactly("offer-10", "offer-9", "offer-9-5");
        ArgumentCaptor<JSONObject> offers = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient, times(3)).createOffer(
                offers.capture(), org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(offers.getAllValues()).extracting(offer -> offer.getString("sku"))
                .containsExactly("sku-a", "sku-c", "sku-b");
        assertThat(offers.getAllValues().getFirst().getInteger("availableQuantity")).isEqualTo(3);
        assertThat(offers.getAllValues().getFirst().getJSONObject("pricingSummary")
                .getJSONObject("price").getBigDecimal("value")).isEqualByComparingTo("139.99");
    }

    @Test
    void sortsCombinedSizeLabelsByTheirNumericSizeWithoutChangingTheLabels() {
        service.publishGroup("group-style-1", List.of(
                requestWithSize("sku-a", "US Shoe Size", "10 Men/11.5 Women"),
                requestWithSize("sku-b", "US Shoe Size", "9.5 Men/11 Women"),
                requestWithSize("sku-c", "US Shoe Size", "9 Men/10.5 Women")));

        assertGroupSizeOrder(List.of("sku-c", "sku-b", "sku-a"),
                List.of("9 Men/10.5 Women", "9.5 Men/11 Women", "10 Men/11.5 Women"));
    }

    @Test
    void insertsNewSizesBeforeBetweenAndAfterExistingSizesAndSortsTheWholeGroup() {
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("old-10", "old-8"), List.of("10", "8", "unused"))));
        for (String size : List.of("10", "8")) {
            when(apiClient.getOffersBySku("old-" + size))
                    .thenReturn(List.of(publishedOffer(
                            "offer-" + size, "old-" + size, "listing-group-456")));
            when(apiClient.getInventoryItem("old-" + size))
                    .thenReturn(Optional.of(inventoryItemWithSize(size)));
        }

        service.publishGroup("group-style-1", List.of(
                requestWithSize("new-11", "US Shoe Size", "11"),
                requestWithSize("new-9-5", "US Shoe Size", "9.5"),
                requestWithSize("new-7", "US Shoe Size", "7"),
                requestWithSize("new-9", "US Shoe Size", "9")));

        assertGroupSizeOrder(
                List.of("new-7", "old-8", "new-9", "new-9-5", "old-10", "new-11"),
                List.of("7", "8", "9", "9.5", "10", "11"));
        verify(apiClient, never()).withdrawOffer(anyString());
    }

    @Test
    void insertsASingleNewHalfSizeBetweenExistingSizes() {
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("old-10", "old-9"), List.of("10", "9"))));
        for (String size : List.of("10", "9")) {
            when(apiClient.getOffersBySku("old-" + size))
                    .thenReturn(List.of(publishedOffer(
                            "offer-" + size, "old-" + size, "listing-group-456")));
            when(apiClient.getInventoryItem("old-" + size))
                    .thenReturn(Optional.of(inventoryItemWithSize(size)));
        }

        service.publishGroup("group-style-1",
                List.of(requestWithSize("new-9-5", "US Shoe Size", "9.5")));

        assertGroupSizeOrder(List.of("old-9", "new-9-5", "old-10"), List.of("9", "9.5", "10"));
    }

    @Test
    void preservesEqualNumericAndUnrecognizedLabelsWhileSortingNumericSizesFirst() {
        service.publishGroup("group-style-1", List.of(
                requestWithSize("sku-custom", "US Shoe Size", "Custom"),
                requestWithSize("sku-10", "US Shoe Size", "10"),
                requestWithSize("sku-9-0", "US Shoe Size", "9.0"),
                requestWithSize("sku-9", "US Shoe Size", "9"),
                requestWithSize("sku-other", "US Shoe Size", "Other")));

        assertGroupSizeOrder(
                List.of("sku-9-0", "sku-9", "sku-10", "sku-custom", "sku-other"),
                List.of("9.0", "9", "10", "Custom", "Other"));
    }

    private EbayListingRequest requestWithSize(String sku, String aspect, String size) {
        EbayListingRequest request = listingRequest();
        request.setSku(sku);
        request.setAspects(Map.of(aspect, List.of(size)));
        return request;
    }

    private void assertGroupSizeOrder(List<String> skus, List<String> sizes) {
        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs")).containsExactlyElementsOf(skus);
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactlyElementsOf(sizes);
    }

    @Test
    void recreatesTheItemGroupWhenGroupPublishFailsWith25001() {
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-10");
        size10.setAspects(new LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        when(apiClient.createOffer(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-9", "offer-10");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenThrow(new EbayApiException(
                        "eBay API request failed (HTTP 500): 25001: Internal Server Error"))
                .thenReturn("listing-group-repaired");

        List<EbayListingResult> results = service.publishGroup(
                "group-style-1", List.of(size9, size10));

        InOrder order = inOrder(apiClient);
        order.verify(apiClient).publishOfferByInventoryItemGroup("group-style-1", "EBAY_US");
        order.verify(apiClient).deleteInventoryItemGroup("group-style-1");
        order.verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        order.verify(apiClient).publishOfferByInventoryItemGroup("group-style-1", "EBAY_US");
        assertThat(results)
                .extracting(EbayListingResult::getListingId)
                .containsOnly("listing-group-repaired");
    }

    @Test
    void doesNotRecreateTheItemGroupForOtherGroupPublishFailures() {
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-10");
        size10.setAspects(new LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        when(apiClient.createOffer(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-9", "offer-10");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenThrow(new EbayApiException(
                        "eBay API request failed (HTTP 400): 25002: The item specific Color is missing."));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.publishGroup(
                        "group-style-1", List.of(size9, size10)))
                .isInstanceOf(EbayApiException.class)
                .hasMessageContaining("25002");

        verify(apiClient, never()).deleteInventoryItemGroup(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retriesTransientInventoryWritesBeforePublishingAGroup() {
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        EbayListingRequest size10 = listingRequest();
        size10.setSku("shoe-sku-10");
        size10.setAspects(new LinkedHashMap<>(size10.getAspects()));
        size10.getAspects().put("US Shoe Size", List.of("10"));
        doThrow(new EbayApiException(
                "eBay API request failed (HTTP 400): 25604: Availability not found"))
                .doNothing()
                .when(apiClient).createOrReplaceInventoryItem(
                        org.mockito.ArgumentMatchers.eq("shoe-sku-9"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("en-US"));
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-9", "offer-10");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenReturn("listing-group-456");

        List<EbayListingResult> results = service.publishGroup(
                "group-style-1", List.of(size9, size10));

        verify(apiClient, times(2)).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-9"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient, times(2)).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(results)
                .extracting(EbayListingResult::getListingId)
                .containsOnly("listing-group-456");
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
    void publishesSoccerCleatSizesUsingTheShortUsSizeAspectName() {
        EbayListingRequest size8 = listingRequest();
        size8.setSku("cleat-sku-8");
        size8.setCategoryId("109133");
        size8.setAspects(new LinkedHashMap<>(Map.of(
                "Brand", List.of("Nike"),
                "US Size", List.of("8"))));
        EbayListingRequest size85 = listingRequest();
        size85.setSku("cleat-sku-8-5");
        size85.setCategoryId("109133");
        size85.setAspects(new LinkedHashMap<>(Map.of(
                "Brand", List.of("Nike"),
                "US Size", List.of("8.5"))));
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-8", "offer-8-5");
        when(apiClient.publishOfferByInventoryItemGroup("group-cleat", "EBAY_US"))
                .thenReturn("listing-cleat");

        service.publishGroup("group-cleat", List.of(size8, size85));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-cleat"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        JSONObject specification = groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0);
        assertThat(specification.getString("name")).isEqualTo("US Size");
        assertThat(specification.getJSONArray("values")).containsExactly("8", "8.5");
    }

    @Test
    void explainsWhenNoSupportedSizeAspectWasResolved() {
        EbayListingRequest first = listingRequest();
        first.setSku("cleat-sku-8");
        first.setAspects(Map.of("Brand", List.of("Nike")));
        EbayListingRequest second = listingRequest();
        second.setSku("cleat-sku-8-5");
        second.setAspects(Map.of("Brand", List.of("Nike")));

        assertThatThrownBy(() -> service.publishGroup(
                "group-cleat", List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未识别到尺码属性");
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

    @Test
    void dropsVariationValuesNoLongerUsedByAnySkuInTheGroup() {
        // 上一次上架失败会把作废的尺码串留在 variesBy 里。重新上架时这些
        // 残留值必须被剔除，否则 eBay 会拿早已不用的值来校验并报 25129。
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-9", "shoe-sku-10"),
                        List.of("9 Men/10.5 Women", "10 Men/11.5 Women", "10"))));
        when(apiClient.getInventoryItem("shoe-sku-10"))
                .thenReturn(Optional.of(inventoryItemWithSize("10")));
        when(apiClient.getOffersBySku("shoe-sku-9"))
                .thenReturn(List.of(publishedOffer(
                        "offer-9", "shoe-sku-9", "listing-group-456")));
        when(apiClient.getOffersBySku("shoe-sku-10"))
                .thenReturn(List.of(publishedOffer(
                        "offer-10", "shoe-sku-10", "listing-group-456")));

        service.publishGroup("group-style-1", List.of(size9));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("9", "10");
    }

    @Test
    void keepsHistoricVariationValuesWhenAnInventoryItemCannotBeRead() {
        // 读不到库存项时宁可多留：一次网络抖动不该把别人的尺码摘掉。
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-9", "shoe-sku-10"), List.of("10", "11"))));
        when(apiClient.getInventoryItem("shoe-sku-10"))
                .thenThrow(new EbayApiException("boom"));
        when(apiClient.getOffersBySku("shoe-sku-9"))
                .thenReturn(List.of(publishedOffer(
                        "offer-9", "shoe-sku-9", "listing-group-456")));
        when(apiClient.getOffersBySku("shoe-sku-10"))
                .thenReturn(List.of(publishedOffer(
                        "offer-10", "shoe-sku-10", "listing-group-456")));

        service.publishGroup("group-style-1", List.of(size9));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("9", "10", "11");
    }

    private JSONObject inventoryItemWithSize(String size) {
        return new JSONObject(true).fluentPut("product", new JSONObject(true)
                .fluentPut("aspects", new JSONObject(true)
                        .fluentPut("US Shoe Size", List.of(size))));
    }

    @Test
    void dropsEndedSkusFromAnExistingGroupBeforeRelisting() {
        // 5/6/7 码曾经上架又整组下架，offer 状态为 ENDED 但仍带旧 listingId。
        // 再上 8 码时不能把这些已下架的 SKU 一起带回 variantSKUs。
        EbayListingRequest size8 = listingRequest();
        size8.setSku("shoe-sku-8");
        size8.setAspects(new LinkedHashMap<>(size8.getAspects()));
        size8.getAspects().put("US Shoe Size", List.of("8"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-5", "shoe-sku-6", "shoe-sku-7"), List.of("5", "6", "7"))));
        for (String sku : List.of("shoe-sku-5", "shoe-sku-6", "shoe-sku-7")) {
            when(apiClient.getOffersBySku(sku))
                    .thenReturn(List.of(endedOffer("offer-" + sku, sku, "listing-old")));
        }
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-8");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenReturn("listing-new");

        List<EbayListingResult> results = service.publishGroup("group-style-1", List.of(size8));

        InOrder order = inOrder(apiClient);
        order.verify(apiClient).deleteInventoryItemGroup("group-style-1");
        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        order.verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        order.verify(apiClient).publishOfferByInventoryItemGroup("group-style-1", "EBAY_US");
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs")).containsExactly("shoe-sku-8");
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("8");
        verify(apiClient, never()).publishOffer(org.mockito.ArgumentMatchers.anyString());
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getOfferId()).isEqualTo("offer-8");
            assertThat(result.getListingId()).isEqualTo("listing-new");
        });
    }

    @Test
    void keepsOnlyActiveSkusWhenAddingASizeToAPartiallyDelistedGroup() {
        // 6 码仍在架、5 码已下架：新上 8 码时组里只应保留 6 和 8。
        EbayListingRequest size8 = listingRequest();
        size8.setSku("shoe-sku-8");
        size8.setAspects(new LinkedHashMap<>(size8.getAspects()));
        size8.getAspects().put("US Shoe Size", List.of("8"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-5", "shoe-sku-6"), List.of("5", "6"))));
        when(apiClient.getOffersBySku("shoe-sku-5"))
                .thenReturn(List.of(endedOffer("offer-5", "shoe-sku-5", "listing-group-456")));
        when(apiClient.getOffersBySku("shoe-sku-6"))
                .thenReturn(List.of(publishedOffer("offer-6", "shoe-sku-6", "listing-group-456")));
        when(apiClient.getInventoryItem("shoe-sku-6"))
                .thenReturn(Optional.of(inventoryItemWithSize("6")));
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-8");
        when(apiClient.publishOffer("offer-8")).thenReturn("listing-group-456");

        service.publishGroup("group-style-1", List.of(size8));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs"))
                .containsExactly("shoe-sku-6", "shoe-sku-8");
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("6", "8");
        verify(apiClient, never()).deleteInventoryItemGroup(org.mockito.ArgumentMatchers.anyString());
        verify(apiClient).publishOffer("offer-8");
    }

    @Test
    void publishesASingleSizeAsAGroupSoLaterSizesCanJoinTheSameListing() {
        // 单尺码若直接发单品 listing，之后补尺码会被 eBay 25704 拒掉，
        // 同一货号就会散成多个 listing。所以单尺码也要走商品组发布。
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-9");
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenReturn("listing-group-456");

        List<EbayListingResult> results = service.publishGroup("group-style-1", List.of(size9));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs")).containsExactly("shoe-sku-9");
        JSONObject specification = groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0);
        assertThat(specification.getString("name")).isEqualTo("US Shoe Size");
        assertThat(specification.getJSONArray("values")).containsExactly("9");
        verify(apiClient, never()).publishOffer(org.mockito.ArgumentMatchers.anyString());
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getOfferId()).isEqualTo("offer-9");
            assertThat(result.getListingId()).isEqualTo("listing-group-456");
        });
    }

    @Test
    void reusesTheLiveSkuWhenAnEuSizeResolvesToAUsSizeAlreadyInTheGroup() {
        // EU38 换算成美码 5.5，而组里已有在架的 USM5.5：同一尺码值在组里只能
        // 出现一次（eBay 25013），应更新那条在架 SKU 而不是再建一个 SKU。
        EbayListingRequest eu38 = listingRequest();
        eu38.setSku("shoe-sku-eu-38");
        eu38.setQuantity(4);
        eu38.setAspects(new LinkedHashMap<>(eu38.getAspects()));
        eu38.getAspects().put("US Shoe Size", List.of("5.5"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-usm-55", "shoe-sku-usm-6"), List.of("5.5", "6"))));
        when(apiClient.getOffersBySku("shoe-sku-usm-55"))
                .thenReturn(List.of(publishedOffer("offer-55", "shoe-sku-usm-55", "listing-group-456")));
        when(apiClient.getOffersBySku("shoe-sku-usm-6"))
                .thenReturn(List.of(publishedOffer("offer-6", "shoe-sku-usm-6", "listing-group-456")));
        when(apiClient.getInventoryItem("shoe-sku-usm-55"))
                .thenReturn(Optional.of(inventoryItemWithSize("5.5")));
        when(apiClient.getInventoryItem("shoe-sku-usm-6"))
                .thenReturn(Optional.of(inventoryItemWithSize("6")));

        List<EbayListingResult> results = service.publishGroup("group-style-1", List.of(eu38));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs"))
                .containsExactly("shoe-sku-usm-55", "shoe-sku-usm-6");
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("5.5", "6");
        verify(apiClient).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-usm-55"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient, never()).createOrReplaceInventoryItem(
                org.mockito.ArgumentMatchers.eq("shoe-sku-eu-38"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(apiClient).updateOffer(
                org.mockito.ArgumentMatchers.eq("offer-55"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US"));
        verify(apiClient, never()).createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(apiClient, never()).publishOffer(org.mockito.ArgumentMatchers.anyString());
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getSku()).isEqualTo("shoe-sku-usm-55");
            assertThat(result.getOfferId()).isEqualTo("offer-55");
            assertThat(result.getListingId()).isEqualTo("listing-group-456");
        });
    }

    @Test
    void withdrawsASingleSkuListingAndRepublishesTheGroupOn25704() {
        // 老版本把 43 码单独发成了单品 listing。再加 44 码时 eBay 报 25704，
        // 应下架那条单品 offer 后整组重新发布，让两个尺码回到同一个 listing。
        EbayListingRequest size43 = listingRequest();
        size43.setSku("shoe-sku-43");
        size43.setAspects(new LinkedHashMap<>(size43.getAspects()));
        size43.getAspects().put("US Shoe Size", List.of("9.5"));
        EbayListingRequest size44 = listingRequest();
        size44.setSku("shoe-sku-44");
        size44.setAspects(new LinkedHashMap<>(size44.getAspects()));
        size44.getAspects().put("US Shoe Size", List.of("10"));
        when(apiClient.getOffersBySku("shoe-sku-43"))
                .thenReturn(List.of(publishedOffer("offer-43", "shoe-sku-43", "listing-single")));
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-44");
        when(apiClient.publishOffer("offer-44"))
                .thenThrow(new EbayApiException("eBay API request failed (HTTP 400): 25704: "
                        + "The following SKU is already listed as a single SKU listing. "
                        + "SKU: shoe-sku-43 Listingid is : listing-single"));
        when(apiClient.publishOfferByInventoryItemGroup("group-style-1", "EBAY_US"))
                .thenReturn("listing-group-new");

        List<EbayListingResult> results = service.publishGroup(
                "group-style-1", List.of(size43, size44));

        InOrder order = inOrder(apiClient);
        order.verify(apiClient).publishOffer("offer-44");
        order.verify(apiClient).withdrawOffer("offer-43");
        order.verify(apiClient).publishOfferByInventoryItemGroup("group-style-1", "EBAY_US");
        assertThat(results)
                .extracting(EbayListingResult::getListingId)
                .containsOnly("listing-group-new");
    }

    @Test
    void dropsOutOfStockSkusFromAnExistingGroupBeforeRelisting() {
        // 定时改价会把没有得物价格的尺码库存置 0，eBay 随即把该变体从 listing
        // 上移掉；之后若仍把它留在 variantSKUs 里，发布会报 25082。
        EbayListingRequest size9 = listingRequest();
        size9.setSku("shoe-sku-9");
        size9.setAspects(new LinkedHashMap<>(size9.getAspects()));
        size9.getAspects().put("US Shoe Size", List.of("9"));
        when(apiClient.getInventoryItemGroup("group-style-1"))
                .thenReturn(Optional.of(inventoryGroup(
                        List.of("shoe-sku-10", "shoe-sku-11"), List.of("10", "11"))));
        when(apiClient.getOffersBySku("shoe-sku-10"))
                .thenReturn(List.of(publishedOffer("offer-10", "shoe-sku-10", "listing-group-456")
                        .fluentPut("availableQuantity", 1)));
        when(apiClient.getOffersBySku("shoe-sku-11"))
                .thenReturn(List.of(publishedOffer("offer-11", "shoe-sku-11", "listing-group-456")
                        .fluentPut("availableQuantity", 0)));
        when(apiClient.getInventoryItem("shoe-sku-10"))
                .thenReturn(Optional.of(inventoryItemWithSize("10")));
        when(apiClient.createOffer(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("en-US")))
                .thenReturn("offer-9");
        when(apiClient.publishOffer("offer-9")).thenReturn("listing-group-456");

        service.publishGroup("group-style-1", List.of(size9));

        ArgumentCaptor<JSONObject> groupPayload = ArgumentCaptor.forClass(JSONObject.class);
        verify(apiClient).createOrReplaceInventoryItemGroup(
                org.mockito.ArgumentMatchers.eq("group-style-1"), groupPayload.capture(),
                org.mockito.ArgumentMatchers.eq("en-US"));
        assertThat(groupPayload.getValue().getJSONArray("variantSKUs"))
                .containsExactly("shoe-sku-9", "shoe-sku-10");
        assertThat(groupPayload.getValue().getJSONObject("variesBy")
                .getJSONArray("specifications").getJSONObject(0).getJSONArray("values"))
                .containsExactly("9", "10");
        verify(apiClient, never()).getInventoryItem("shoe-sku-11");
    }

    private JSONObject endedOffer(String offerId, String sku, String listingId) {
        return new JSONObject(true)
                .fluentPut("offerId", offerId)
                .fluentPut("sku", sku)
                .fluentPut("marketplaceId", "EBAY_US")
                .fluentPut("status", "PUBLISHED")
                .fluentPut("listing", new JSONObject(true)
                        .fluentPut("listingId", listingId)
                        .fluentPut("listingStatus", "ENDED"));
    }
}
