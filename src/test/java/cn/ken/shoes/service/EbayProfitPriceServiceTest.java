package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayMarketingApiClient;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayProfitTargetExcel;
import cn.ken.shoes.service.EbayProfitPriceService.Action;
import cn.ken.shoes.service.EbayProfitPriceService.Decision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EbayProfitPriceServiceTest {

    private static final BigDecimal RATE = new BigDecimal("6.7");
    private static final BigDecimal FREIGHT = new BigDecimal("25");
    private static final BigDecimal AD = new BigDecimal("4");

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private EbayListingMapper listingMapper;
    private EbaySellApiClient sellClient;
    private EbayTradingApiClient tradingClient;
    private EbayMarketingApiClient marketingClient;
    private PoisonClient poisonClient;
    private TaskInputSnapshotStore snapshotStore;
    private EbayProfitPriceService service;
    private List<Runnable> started;
    private Double oldRate;
    private Integer oldFreight;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        listingMapper = mock(EbayListingMapper.class);
        sellClient = mock(EbaySellApiClient.class);
        tradingClient = mock(EbayTradingApiClient.class);
        marketingClient = mock(EbayMarketingApiClient.class);
        poisonClient = mock(PoisonClient.class);
        snapshotStore = mock(TaskInputSnapshotStore.class);
        EbayProperties properties = new EbayProperties();
        properties.setDefaultMarketplaceId("EBAY_US");
        properties.setDefaultCurrency("USD");
        oldRate = PriceSwitch.EXCHANGE_RATE;
        oldFreight = PriceSwitch.FREIGHT;
        PriceSwitch.EXCHANGE_RATE = 6.7d;
        PriceSwitch.FREIGHT = 25;
        started = new ArrayList<>();
        service = new EbayProfitPriceService(taskMapper, taskItemMapper, listingMapper, sellClient, tradingClient,
                marketingClient, poisonClient, snapshotStore, properties, started::add);
        when(sellClient.bulkUpdatePriceQuantity(anyList())).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(u.sku(), u.offerId(), 200, null))
                    .toList();
        });
    }

    @AfterEach
    void tearDown() {
        PriceSwitch.EXCHANGE_RATE = oldRate;
        PriceSwitch.FREIGHT = oldFreight;
    }

    // x=150, g=4% -> 利润 = 128.05065*6.7 - 得物价 - 25 = 857.94 - 得物价 - 25

    @Test
    void lowersPriceWhenStillProfitableAfterOneDollar() {
        // x=150 利润 832.94-500=332.94；x=149 走 12.7% 档位，仍远大于 100
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 1, new BigDecimal("500"), AD,
                new BigDecimal("100"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.LOWER_PRICE);
        assertThat(d.newPrice()).isEqualByComparingTo("149");
        assertThat(d.newQuantity()).isNull();
        assertThat(d.profit()).isEqualByComparingTo("332.94");
    }

    @Test
    void keepsPriceWhenDroppingAcross150ThresholdLosesProfit() {
        // x=150 利润 332.94；x=149 成交费变 12.7%，利润掉到约 268，目标 300 时不降价
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 1, new BigDecimal("500"), AD,
                new BigDecimal("300"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.KEEP);
        assertThat(d.profitAfterStep()).isLessThan(new BigDecimal("300"));
    }

    @Test
    void zeroesStockWhenNotProfitable() {
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 2, new BigDecimal("500"), AD,
                new BigDecimal("332.94"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.ZERO_STOCK);
        assertThat(d.newQuantity()).isZero();
        assertThat(d.newPrice()).isNull();
    }

    @Test
    void alreadyZeroWhenNotProfitableAndNoStock() {
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 0, new BigDecimal("900"), AD,
                new BigDecimal("0"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.ALREADY_ZERO);
    }

    @Test
    void restoresStockWhenProfitableAndOutOfStock() {
        Decision keep = EbayProfitPriceService.decide(new BigDecimal("150"), 0, new BigDecimal("500"), AD,
                new BigDecimal("300"), RATE, FREIGHT);
        assertThat(keep.action()).isEqualTo(Action.RESTORE);
        assertThat(keep.newQuantity()).isEqualTo(1);
        assertThat(keep.newPrice()).isNull();

        Decision lower = EbayProfitPriceService.decide(new BigDecimal("150"), 0, new BigDecimal("500"), AD,
                new BigDecimal("100"), RATE, FREIGHT);
        assertThat(lower.action()).isEqualTo(Action.LOWER_AND_RESTORE);
        assertThat(lower.newPrice()).isEqualByComparingTo("149");
        assertThat(lower.newQuantity()).isEqualTo(1);
    }

    @Test
    void treatsMissingPoisonPriceAsNotProfitable() {
        assertThat(EbayProfitPriceService.decide(new BigDecimal("150"), 1, null, AD, BigDecimal.ZERO, RATE, FREIGHT)
                .action()).isEqualTo(Action.ZERO_STOCK);
        assertThat(EbayProfitPriceService.decide(new BigDecimal("150"), 0, null, AD, BigDecimal.ZERO, RATE, FREIGHT)
                .action()).isEqualTo(Action.ALREADY_ZERO);
    }

    @Test
    void runsOneRoundUsingLivePricesAdRatesAndPerStyleTargets() {
        when(listingMapper.selectActive()).thenReturn(List.of(
                listing("SKU-A", "offer-A", "AAA-1", "HK"),
                listing("SKU-B", "offer-B", "BBB-2", "HK"),
                listing("SKU-US", "offer-US", "CCC-3", "US")));
        when(tradingClient.getActiveSkus(any())).thenReturn(List.of(
                new EbayTradingApiClient.ActiveSku("SKU-A", "L-A", new BigDecimal("150"), 1),
                new EbayTradingApiClient.ActiveSku("SKU-B", "L-B", new BigDecimal("150"), 1)));
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of("L-A", AD));
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(
                poison("AAA-1", 500), poison("BBB-2", 500)));

        // A：默认目标 100，广告 4% -> 降价；B：Excel 设目标 900 -> 不盈利，清库存
        service.runSingleRound(7L, 1, new BigDecimal("100"), Map.of("BBB-2", new BigDecimal("900")),
                new AtomicBoolean());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EbaySellApiClient.OfferPriceQuantity>> sent = ArgumentCaptor.forClass(List.class);
        verify(sellClient).bulkUpdatePriceQuantity(sent.capture());
        assertThat(sent.getValue()).hasSize(2);
        EbaySellApiClient.OfferPriceQuantity a = sent.getValue().stream().filter(u -> u.sku().equals("SKU-A"))
                .findFirst().orElseThrow();
        assertThat(a.price()).isEqualByComparingTo("149");
        assertThat(a.availableQuantity()).isNull();
        EbaySellApiClient.OfferPriceQuantity b = sent.getValue().stream().filter(u -> u.sku().equals("SKU-B"))
                .findFirst().orElseThrow();
        assertThat(b.price()).isNull();
        assertThat(b.availableQuantity()).isZero();

        verify(poisonClient).batchQueryPrice(argThat(list -> !list.contains("CCC-3")));
        ArgumentCaptor<TaskItemDO> items = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper, times(2)).insert(items.capture());
        assertThat(items.getAllValues()).extracting(TaskItemDO::getOperateResult)
                .anySatisfy(r -> assertThat(r).startsWith("降价").contains("广告4%"))
                .anySatisfy(r -> assertThat(r).startsWith("不盈利，库存置0").contains("目标¥900"));
        verify(listingMapper).updatePriceAndQuantity("SKU-A", new BigDecimal("149.00"), 1);
        verify(listingMapper).updatePriceAndQuantity("SKU-B", new BigDecimal("150"), 0);
    }

    @Test
    void abortsRoundWithoutChangesWhenAdRatesUnavailable() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("SKU-A", "offer-A", "AAA-1", "HK")));
        when(tradingClient.getActiveSkus(any())).thenReturn(List.of(
                new EbayTradingApiClient.ActiveSku("SKU-A", "L-A", new BigDecimal("150"), 1)));
        when(marketingClient.getRunningAdRates()).thenThrow(new RuntimeException("403"));

        assertThatThrownBy(() -> service.runSingleRound(7L, 1, BigDecimal.ZERO, Map.of(), new AtomicBoolean()))
                .hasMessageContaining("403");
        verify(sellClient, never()).bulkUpdatePriceQuantity(anyList());
    }

    @Test
    void refusesToStartWhileLegacyPriceSyncRunning() {
        when(taskMapper.selectRunningTask("ebay", EbayPriceSyncService.TASK_TYPE, "running"))
                .thenReturn(new TaskDO());
        assertThat(service.start(1, new BigDecimal("50"), List.of())).isNull();
        verify(taskMapper, never()).insert(any(TaskDO.class));
    }

    @Test
    void startsAndSavesOverrideSnapshot() {
        EbayProfitTargetExcel row = new EbayProfitTargetExcel();
        row.setStyleId(" dd1391-100 ");
        row.setTargetProfit(new BigDecimal("80"));
        doAnswer(invocation -> {
            ((TaskDO) invocation.getArgument(0)).setId(55L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));

        assertThat(service.start(2, new BigDecimal("50"), List.of(row))).isEqualTo(55L);
        assertThat(started).hasSize(1);
        verify(snapshotStore).saveEbayProfitTargets(eq(55L), argThat(list -> list.size() == 1
                && "DD1391-100".equals(list.get(0).getStyleId())));
        assertThat(service.canRun()).isFalse();
    }

    @Test
    void rejectsInvalidExcelRows() {
        EbayProfitTargetExcel row = new EbayProfitTargetExcel();
        row.setStyleId("AAA");
        assertThatThrownBy(() -> EbayProfitPriceService.normalizeOverrides(List.of(row)))
                .hasMessageContaining("第2行目标盈利无效");
    }

    private static EbayListingDO listing(String sku, String offerId, String styleId, String region) {
        EbayListingDO listing = new EbayListingDO();
        listing.setSku(sku);
        listing.setOfferId(offerId);
        listing.setStyleId(styleId);
        listing.setEuSize("42");
        listing.setRegion(region);
        return listing;
    }

    private static PoisonPriceDO poison(String modelNo, int price) {
        PoisonPriceDO p = new PoisonPriceDO();
        p.setModelNo(modelNo);
        p.setEuSize("42");
        p.setPrice(price);
        return p;
    }
}
