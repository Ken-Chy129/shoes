package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayAnalyticsApiClient;
import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.client.EbayTradingApiClient.ListingVariation;
import cn.ken.shoes.client.EbayTradingApiClient.SellerListing;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayItemIdExcel;
import cn.ken.shoes.model.excel.EbaySetPriceExcel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayListingManageServiceTest {

    @TempDir
    Path tempDir;

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private EbayListingMapper listingMapper;
    private EbayTradingApiClient trading;
    private EbaySellApiClient sell;
    private EbayAnalyticsApiClient analytics;
    private EbayListingManageService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        listingMapper = mock(EbayListingMapper.class);
        trading = mock(EbayTradingApiClient.class);
        sell = mock(EbaySellApiClient.class);
        analytics = mock(EbayAnalyticsApiClient.class);
        EbayProperties properties = new EbayProperties();
        service = new EbayListingManageService(taskMapper, taskItemMapper, listingMapper, trading, sell, analytics,
                mock(TaskInputSnapshotStore.class), properties, Runnable::run,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC), tempDir);
        when(listingMapper.selectBySkus(anyList())).thenReturn(List.of());
        when(listingMapper.selectByListingIds(anyList())).thenReturn(List.of());
    }

    private static SellerListing listing(String itemId, String status, String parent, ListingVariation... variations) {
        return new SellerListing(itemId, "Nike Dunk Low DD1391-100", "G1", status,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "P2DT10H37M34S", 5,
                "Athletic Shoes", null, "FixedPriceItem", parent, null, null, List.of(variations));
    }

    @Test
    void parsesItemIdsFromNumbersLinksAndScientificNotation() {
        assertThat(EbayListingManageService.parseItemId("407333465699")).isEqualTo("407333465699");
        assertThat(EbayListingManageService.parseItemId("https://www.ebay.com/itm/407333465699?x=1"))
                .isEqualTo("407333465699");
        assertThat(EbayListingManageService.parseItemId("https://www.ebay.com/itm/Nike-Dunk/407333465699"))
                .isEqualTo("407333465699");
        assertThat(EbayListingManageService.parseItemId("4.07333465699E11")).isEqualTo("407333465699");
        assertThat(EbayListingManageService.parseItemId("abc")).isNull();
    }

    @Test
    void normalizesPriceTargetsAndRejectsBadRows() {
        EbaySetPriceExcel row = new EbaySetPriceExcel();
        row.setItemId("407333465699");
        row.setSize("US 9.50");
        row.setPrice("$129.9");
        EbaySetPriceExcel empty = new EbaySetPriceExcel();
        empty.setItemId("407333465699");
        empty.setSize("10");

        var targets = EbayListingManageService.normalizePriceTargets(List.of(row, empty));

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).size()).isEqualTo("9.5");
        assertThat(targets.get(0).price()).isEqualByComparingTo("129.90");

        row.setPrice("1.234");
        assertThatThrownBy(() -> EbayListingManageService.normalizePriceTargets(List.of(row)))
                .hasMessageContaining("两位小数");
    }

    @Test
    void matchesSizeOrSku() {
        SellerListing listing = listing("1", "Active", null,
                new ListingVariation("S9", "9", new BigDecimal("100"), 1, 0),
                new ListingVariation("S95", "9.5", new BigDecimal("110"), 1, 0));

        assertThat(EbayListingManageService.match(listing,
                new EbayListingManageService.PriceTarget("1", "9.50", null, BigDecimal.TEN)).sku()).isEqualTo("S95");
        assertThat(EbayListingManageService.match(listing,
                new EbayListingManageService.PriceTarget("1", null, "s9", BigDecimal.TEN)).size()).isEqualTo("9");
        assertThatThrownBy(() -> EbayListingManageService.match(listing,
                new EbayListingManageService.PriceTarget("1", "11", null, BigDecimal.TEN)))
                .hasMessageContaining("没有尺码 11");
    }

    @Test
    void buildsListingRowWithOnSaleSizesPriceRangeAndStyleFromMapping() {
        EbayListingDO mapping = new EbayListingDO();
        mapping.setSku("S9");
        mapping.setListingId("1");
        mapping.setStyleId("DD1391-100");
        mapping.setBrand("Nike");
        mapping.setEuSize("42.5");
        when(listingMapper.selectBySkus(anyList())).thenReturn(List.of(mapping));
        SellerListing listing = listing("1", "Active", null,
                new ListingVariation("S9", "9", new BigDecimal("100"), 2, 1),
                new ListingVariation("S10", "10", new BigDecimal("150"), 1, 1),
                new ListingVariation("S85", "8.5", new BigDecimal("90"), 1, 0));

        var row = service.toRow(listing, service.lookup(List.of(listing)), 12);

        assertThat(row.brand()).isEqualTo("Nike");
        assertThat(row.styleId()).isEqualTo("DD1391-100");
        assertThat(row.sizes()).isEqualTo("8.5,9");
        assertThat(row.quantity()).isEqualTo(2);
        assertThat(row.sold()).isEqualTo(2);
        assertThat(row.minPrice()).isEqualByComparingTo("90");
        assertThat(row.maxPrice()).isEqualByComparingTo("100");
        assertThat(row.timeLeft()).isEqualTo("2天10小时");
        assertThat(row.views()).isEqualTo(12);
        assertThat(row.source()).isEqualTo("系统上架");
        assertThat(row.sizeRows()).hasSize(3);
        assertThat(row.sizeRows().get(0).euSize()).isEqualTo("42.5");
    }

    @Test
    void styleFallsBackToTitleWhenNoMapping() {
        assertThat(EbayListingManageService.styleFromTitle("Nike Air Force 1 Low White CZ1055-100"))
                .isEqualTo("CZ1055-100");
    }

    @Test
    void fetchSavesReportAndStillSucceedsWhenViewsUnavailable() {
        SellerListing active = listing("1", "Active", "0", new ListingVariation("S9", "9", BigDecimal.TEN, 1, 0));
        when(trading.getSellerListings(any(), any(), anyInt()))
                .thenReturn(new EbayTradingApiClient.SellerListingPage(1, 1, 1, List.of(active)));
        when(analytics.getListingViews(anyList(), any(), any(), anyString()))
                .thenThrow(new EbayApiException("eBay Analytics API无权限"));

        service.runFetch(7L, EbayListingManageService.SCOPE_ACTIVE, 30, new AtomicBoolean());

        verify(taskItemMapper).insert(any(TaskItemDO.class));
        verify(taskMapper).updateTaskFailReason(eq(7L), org.mockito.ArgumentMatchers.contains("浏览人数获取失败"));
        verify(taskMapper).updateTaskStatus(7L, "success");
        var report = service.loadReport(7L).orElseThrow();
        assertThat(report).hasSize(1);
        assertThat(report.get(0).views()).isNull();
        assertThat(report.get(0).sizeRows()).hasSize(1);
    }

    @Test
    void viewsDateRangeUsesPacificDateSoEndIsNeverInTheFuture() {
        SellerListing active = listing("1", "Active", null, new ListingVariation("S9", "9", BigDecimal.TEN, 1, 0));
        when(trading.getSellerListings(any(), any(), anyInt()))
                .thenReturn(new EbayTradingApiClient.SellerListingPage(1, 1, 1, List.of(active)));
        when(analytics.getListingViews(anyList(), any(), any(), anyString())).thenReturn(java.util.Map.of("1", 42));

        // 固定时钟 2026-11-09T00:00Z：北京已是 11-09 08:00，美西还是 11-08
        service.runFetch(11L, EbayListingManageService.SCOPE_ACTIVE, 30, new AtomicBoolean());

        verify(analytics).getListingViews(anyList(), eq(java.time.LocalDate.parse("2026-10-10")),
                eq(java.time.LocalDate.parse("2026-11-08")), anyString());
        assertThat(service.loadReport(11L).orElseThrow().get(0).views()).isEqualTo(42);
    }

    @Test
    void endUsesEndItemForNonSystemListingAndSkipsEndedOnes() {
        when(trading.getItem("1")).thenReturn(listing("1", "Active", "9", new ListingVariation("S9", "9", BigDecimal.TEN, 1, 0)));
        when(trading.getItem("2")).thenReturn(listing("2", "Completed", null, new ListingVariation("S9", "9", BigDecimal.TEN, 1, 0)));
        when(trading.endItem("1")).thenReturn(true);

        service.runEnd(8L, List.of("1", "2"), new AtomicBoolean());

        verify(trading).endItem("1");
        verify(trading, never()).endItem("2");
        verify(sell, never()).withdrawOfferByInventoryItemGroup(anyString(), anyString());
        verify(taskMapper).updateTaskStatus(8L, "success");
    }

    @Test
    void relistReturnsNewItemIdAndRelinksMapping() {
        when(trading.getItem("1")).thenReturn(listing("1", "Completed", null, new ListingVariation("S9", "9", BigDecimal.TEN, 1, 0)));
        when(trading.relistFixedPriceItem("1")).thenReturn("99");

        service.runRelist(9L, List.of("1"), new AtomicBoolean());

        verify(listingMapper).relinkListing("1", "99");
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).updateById(item.capture());
        assertThat(item.getValue().getOfferId()).isEqualTo("99");
    }

    @Test
    void setPriceRevisesListingAndSyncsSystemOffer() {
        EbayListingDO mapping = new EbayListingDO();
        mapping.setSku("S9");
        mapping.setOfferId("OF1");
        mapping.setListingId("1");
        when(listingMapper.selectBySkus(anyList())).thenReturn(List.of(mapping));
        when(trading.getItem("1")).thenReturn(listing("1", "Active", null,
                new ListingVariation("S9", "9", new BigDecimal("140.18"), 1, 0),
                new ListingVariation("S10", "10", new BigDecimal("150"), 1, 0)));
        when(sell.bulkUpdatePriceQuantity(anyList())).thenReturn(List.of(
                new EbaySellApiClient.OfferUpdateResult("S9", "OF1", 200, null)));

        service.runSetPrice(10L, List.of(
                new EbayListingManageService.PriceTarget("1", "9", null, new BigDecimal("130.85")),
                new EbayListingManageService.PriceTarget("1", "10", null, new BigDecimal("150.00"))),
                new AtomicBoolean());

        ArgumentCaptor<List<EbayTradingApiClient.InventoryRevision>> revisions = ArgumentCaptor.forClass(List.class);
        verify(trading).reviseInventoryStatus(revisions.capture());
        assertThat(revisions.getValue().get(0).sku()).isEqualTo("S9");
        assertThat(revisions.getValue().get(0).price()).isEqualByComparingTo("130.85");
        verify(sell).bulkUpdatePriceQuantity(anyList());
        verify(taskMapper).updateTaskStatus(10L, "success");
        verify(taskMapper, atLeastOnce()).updateTaskAttributes(eq(10L), anyString());
    }

    @Test
    void rejectsEmptyItemIdInput() {
        assertThatThrownBy(() -> EbayListingManageService.normalizeItemIds(List.of(new EbayItemIdExcel())))
                .hasMessageContaining("没有物品编号");
    }
}
