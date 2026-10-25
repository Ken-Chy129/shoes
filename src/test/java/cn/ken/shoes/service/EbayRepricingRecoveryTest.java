package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbaySellApiClient.OfferPriceQuantity;
import cn.ken.shoes.client.EbaySellApiClient.OfferUpdateResult;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EbayRepricingRecoveryTest {

    private EbaySellApiClient client;
    private EbayListingMapper mapper;
    private EbayRepricingRecovery recovery;
    private final List<Long> sleeps = new ArrayList<>();
    private final String hkGroup = EbayListingFactory.inventoryGroupKey("DD1391-100", EbayListingRegion.HK);

    @BeforeEach
    void setUp() {
        client = mock(EbaySellApiClient.class);
        mapper = mock(EbayListingMapper.class);
        EbayProperties properties = new EbayProperties();
        properties.setDefaultMarketplaceId("EBAY_US");
        recovery = new EbayRepricingRecovery(client, mapper, properties, sleeps::add);
    }

    @Test
    void groupKeyMatchesTheKeyUsedWhenListing() {
        EbayListingFactory factory = new EbayListingFactory(new EbayProperties(), null);
        assertThat(EbayListingFactory.inventoryGroupKey("dd1391-100", EbayListingRegion.US))
                .isEqualTo(factory.groupKey("dd1391-100", EbayListingRegion.US));
    }

    @Test
    void republishesTheGroupOnceAndRetriesDetachedSizes() {
        answer(u -> u.offerId().equals("o3") ? ok(u) : null, // first call: o1,o2 detached
               u -> ok(u));
        // first bulk: o1/o2 -> 25013, o3 ok; second bulk (retry after republish): all ok
        List<EbayRepricingRecovery.Resolution> results = recovery.newRound().apply(List.of(
                price("o1", 1), price("o2", 1), price("o3", 1)));

        verify(client, times(1)).publishOfferByInventoryItemGroup(hkGroup, "EBAY_US");
        assertThat(results).extracting(EbayRepricingRecovery.Resolution::kind)
                .containsOnly(EbayRepricingRecovery.Kind.UPDATED);
        assertThat(results.get(0).note()).contains("整组重新发布");
        assertThat(results.get(2).note()).isNull();
    }

    @Test
    void doesNotRepublishForOutOfStockOrZeroingDetachedSizes() {
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            return updates.stream().map(this::detached).toList();
        });

        List<EbayRepricingRecovery.Resolution> results = recovery.newRound().apply(List.of(
                price("o1", 0), zero("o2")));

        verify(client, never()).publishOfferByInventoryItemGroup(anyString(), anyString());
        assertThat(results.get(0).kind()).isEqualTo(EbayRepricingRecovery.Kind.DETACHED_OUT_OF_STOCK);
        assertThat(results.get(1).kind()).isEqualTo(EbayRepricingRecovery.Kind.UPDATED);
    }

    @Test
    void withdrawsTheWholeGroupWhenEbayRefusesToZeroTheLastSize() {
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            return updates.stream().map(u -> new OfferUpdateResult(u.sku(), u.offerId(), 400,
                    "HTTP 400: 25004: At least one of the variations must have a quantity greater than 0.")).toList();
        });
        when(client.withdrawOfferByInventoryItemGroup(hkGroup, "EBAY_US")).thenReturn(true);
        EbayRepricingRecovery.Round round = recovery.newRound();

        List<EbayRepricingRecovery.Resolution> results = round.apply(List.of(zero("o1")));
        // 同组后续批次直接跳过，不再请求 eBay。
        List<EbayRepricingRecovery.Resolution> later = round.apply(List.of(zero("o2")));

        verify(client, times(1)).withdrawOfferByInventoryItemGroup(hkGroup, "EBAY_US");
        verify(mapper).markGroupEnded("DD1391-100", "hk");
        verify(client, times(1)).bulkUpdatePriceQuantity(anyList());
        assertThat(results.get(0).kind()).isEqualTo(EbayRepricingRecovery.Kind.GROUP_WITHDRAWN);
        assertThat(later.get(0).kind()).isEqualTo(EbayRepricingRecovery.Kind.GROUP_WITHDRAWN);
    }

    @Test
    void reports25004AsFailedWhenWithdrawFails() {
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            return updates.stream().map(u -> new OfferUpdateResult(u.sku(), u.offerId(), 400,
                    "HTTP 400: 25004: quantity")).toList();
        });
        when(client.withdrawOfferByInventoryItemGroup(anyString(), anyString()))
                .thenThrow(new EbayApiException("eBay API request failed (HTTP 400): 25001: boom"));

        EbayRepricingRecovery.Resolution result = recovery.newRound().apply(List.of(zero("o1"))).get(0);

        assertThat(result.failed()).isTrue();
        assertThat(result.error()).contains("整组下架失败").contains("25001");
        verify(mapper, never()).markGroupEnded(anyString(), anyString());
    }

    @Test
    void stopsRepricingListingsRemovedForPolicyViolations() {
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            return updates.stream().map(u -> new OfferUpdateResult(u.sku(), u.offerId(), 400,
                    "HTTP 400: 25097: This listing has been removed for violating Counterfeit items policy.")).toList();
        });

        EbayRepricingRecovery.Resolution result = recovery.newRound().apply(List.of(price("o1", 1))).get(0);

        assertThat(result.kind()).isEqualTo(EbayRepricingRecovery.Kind.REMOVED_BY_EBAY);
        verify(mapper).markGroupEnded("DD1391-100", "hk");
    }

    @Test
    void splitsARejectedBatchToIsolateTheBadOffer() {
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            if (updates.stream().anyMatch(u -> u.offerId().equals("bad"))) {
                throw new EbayApiException("eBay API request failed (HTTP 400): provider error");
            }
            return updates.stream().map(this::ok).toList();
        });

        List<EbayRepricingRecovery.Resolution> results = recovery.newRound().apply(List.of(
                price("o1", 1), price("o2", 1), price("bad", 1), price("o4", 1), price("o5", 1)));

        assertThat(results).filteredOn(EbayRepricingRecovery.Resolution::failed)
                .extracting(EbayRepricingRecovery.Resolution::offerId).containsExactly("bad");
        assertThat(results.get(2).error()).contains("provider error");
    }

    @Test
    void doesNotSplitOnNetworkFailures() {
        when(client.bulkUpdatePriceQuantity(anyList()))
                .thenThrow(new EbayApiException("eBay API request failed due to a network error"));

        List<EbayRepricingRecovery.Resolution> results = recovery.newRound().apply(List.of(
                price("o1", 1), price("o2", 1)));

        verify(client, times(1)).bulkUpdatePriceQuantity(anyList());
        assertThat(results).allMatch(EbayRepricingRecovery.Resolution::failed);
    }

    @Test
    void retriesPerOfferServerErrors() {
        Set<String> seen = new java.util.HashSet<>();
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            return updates.stream().map(u -> seen.add(u.offerId()) && u.offerId().equals("o2")
                    ? new OfferUpdateResult(u.sku(), u.offerId(), 500,
                    "HTTP 500: 25002: A user error has occurred. System error.")
                    : ok(u)).toList();
        });

        List<EbayRepricingRecovery.Resolution> results = recovery.newRound().apply(List.of(
                price("o1", 1), price("o2", 1)));

        assertThat(results).noneMatch(EbayRepricingRecovery.Resolution::failed);
        verify(client, times(2)).bulkUpdatePriceQuantity(anyList());
        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    void givesUpOnPersistentServerErrors() {
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            return updates.stream().map(u -> new OfferUpdateResult(u.sku(), u.offerId(), 500,
                    "HTTP 500: 25002: System error.")).toList();
        });

        EbayRepricingRecovery.Resolution result = recovery.newRound().apply(List.of(price("o1", 1))).get(0);

        assertThat(result.failed()).isTrue();
        verify(client, times(1 + EbayRepricingRecovery.MAX_TRANSIENT_RETRIES)).bulkUpdatePriceQuantity(anyList());
    }

    // ---------- helpers ----------

    /** 第一次调用按 first（返回 null 表示 25013），之后按 rest。 */
    @SafeVarargs
    private void answer(Function<OfferPriceQuantity, OfferUpdateResult> first,
                        Function<OfferPriceQuantity, OfferUpdateResult>... rest) {
        int[] call = {0};
        when(client.bulkUpdatePriceQuantity(anyList())).thenAnswer(inv -> {
            List<OfferPriceQuantity> updates = inv.getArgument(0);
            Function<OfferPriceQuantity, OfferUpdateResult> f = call[0]++ == 0 ? first : rest[0];
            return updates.stream().map(u -> {
                OfferUpdateResult r = f.apply(u);
                return r == null ? detached(u) : r;
            }).toList();
        });
    }

    private OfferUpdateResult ok(OfferPriceQuantity u) {
        return new OfferUpdateResult(u.sku(), u.offerId(), 200, null);
    }

    private OfferUpdateResult detached(OfferPriceQuantity u) {
        return new OfferUpdateResult(u.sku(), u.offerId(), 400,
                "HTTP 400: 25013: Invalid data in the Inventory Item Group. Missing name in the "
                        + "variation specifics or variation specifics set.");
    }

    private EbayRepricingRecovery.Target price(String offerId, int knownQuantity) {
        return new EbayRepricingRecovery.Target(new OfferPriceQuantity("SKU-" + offerId, offerId,
                new BigDecimal("99.00"), "USD", null), "DD1391-100", "hk", knownQuantity);
    }

    private EbayRepricingRecovery.Target zero(String offerId) {
        return new EbayRepricingRecovery.Target(new OfferPriceQuantity("SKU-" + offerId, offerId,
                null, "USD", 0), "DD1391-100", null, 1);
    }
}
