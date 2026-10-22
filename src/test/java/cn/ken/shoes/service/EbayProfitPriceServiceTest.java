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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class EbayProfitPriceServiceTest {

    private static final BigDecimal RATE = new BigDecimal("6.7");
    private static final BigDecimal FREIGHT = new BigDecimal("25");
    private static final BigDecimal AD = new BigDecimal("4");

    @Test
    void usesExactProfitForRaisingKeepingAndLoweringAtBoundary() {
        BigDecimal cost = new BigDecimal("103");
        BigDecimal target = new BigDecimal("50");
        Decision raised = EbayProfitPriceService.decide(new BigDecimal("36"), 1, cost, AD, target, RATE, FREIGHT);
        assertThat(raised.newPrice()).isEqualByComparingTo("36.40");
        assertThat(raised.profitAfterStep()).isEqualByComparingTo("50.001044");
        assertThat(EbayProfitCalculator.exactProfit(new BigDecimal("36.39"), AD, RATE, cost, FREIGHT))
                .isLessThan(target);
        Decision kept = EbayProfitPriceService.decide(new BigDecimal("36.40"), 1, cost, AD, target, RATE, FREIGHT);
        assertThat(kept.action()).isEqualTo(Action.KEEP);
        Decision lowered = EbayProfitPriceService.decide(new BigDecimal("37.40"), 1, cost, AD, target, RATE, FREIGHT);
        assertThat(lowered.action()).isEqualTo(Action.LOWER_PRICE);
        assertThat(lowered.newPrice()).isEqualByComparingTo("36.40");
        // Exact equality still fails the strict > comparison.
        Decision equal = EbayProfitPriceService.decide(new BigDecimal("36.40"), 1, cost, AD,
                new BigDecimal("50.001044"), RATE, FREIGHT);
        assertThat(equal.action()).isEqualTo(Action.RAISE_PRICE);
        assertThat(equal.newPrice()).isEqualByComparingTo("36.41");
    }

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
    void lowersDirectlyToLowestProfitablePriceInsteadOfOneDollarStep() {
        // x=150 利润 332.94，目标 100：直接跳到最低盈利价，远低于 149
        BigDecimal poison = new BigDecimal("500");
        BigDecimal target = new BigDecimal("100");
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 1, poison, AD, target, RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.LOWER_PRICE);
        assertThat(d.newPrice()).isLessThan(new BigDecimal("149"));
        assertThat(d.newQuantity()).isNull();
        assertThat(d.profit()).isEqualByComparingTo("332.939355");
        assertThat(d.profitAfterStep()).isGreaterThan(target);
        assertThat(EbayProfitCalculator.exactProfit(d.newPrice().subtract(new BigDecimal("0.01")), AD, RATE,
                poison, FREIGHT)).isLessThanOrEqualTo(target);
        // 再跑一轮价格不再变化
        Decision again = EbayProfitPriceService.decide(d.newPrice(), 1, poison, AD, target, RATE, FREIGHT);
        assertThat(again.action()).isEqualTo(Action.KEEP);
    }

    @Test
    void lowersHighPriceTo150WhenOnlyHighFeeTierIsProfitable() {
        // 目标 300：<150 走 12.7% 档位都不盈利，≥150 才盈利，最低盈利价正好是 150.00
        Decision d = EbayProfitPriceService.decide(new BigDecimal("200"), 1, new BigDecimal("500"), AD,
                new BigDecimal("300"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.LOWER_PRICE);
        assertThat(d.newPrice()).isEqualByComparingTo("150.00");
        assertThat(d.profitAfterStep()).isGreaterThan(new BigDecimal("300"));
    }

    @Test
    void keepsPriceWhenAlreadyAtLowestProfitablePrice() {
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 1, new BigDecimal("500"), AD,
                new BigDecimal("300"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.KEEP);
        assertThat(d.newPrice()).isNull();
    }

    @Test
    void raisesToLowestCentThatIsProfitable() {
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 2, new BigDecimal("500"), AD,
                new BigDecimal("332.94"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.RAISE_PRICE);
        assertThat(d.newPrice()).isGreaterThan(new BigDecimal("150"));
        assertThat(d.profitAfterStep()).isGreaterThan(new BigDecimal("332.94"));
        assertThat(EbayProfitCalculator.exactProfit(d.newPrice().subtract(new BigDecimal("0.01")), AD, RATE,
                new BigDecimal("500"), FREIGHT)).isLessThanOrEqualTo(new BigDecimal("332.94"));
        assertThat(d.newQuantity()).isNull();
    }

    @Test
    void raisesAndRestoresWhenNotProfitableAndNoStock() {
        Decision d = EbayProfitPriceService.decide(new BigDecimal("150"), 0, new BigDecimal("900"), AD,
                new BigDecimal("0"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.RAISE_AND_RESTORE);
        assertThat(d.newPrice()).isGreaterThan(new BigDecimal("150"));
        assertThat(d.newQuantity()).isEqualTo(1);
    }

    @Test
    void raisingCanStopExactlyAtFeeThreshold() {
        Decision d = EbayProfitPriceService.decide(new BigDecimal("149"), 1, new BigDecimal("500"), AD,
                new BigDecimal("300"), RATE, FREIGHT);
        assertThat(d.action()).isEqualTo(Action.RAISE_PRICE);
        assertThat(d.newPrice()).isEqualByComparingTo("150.00");
        assertThat(d.profitAfterStep()).isGreaterThan(new BigDecimal("300"));
    }

    @Test
    void findsMinimalProfitableCentOnBothSidesOf150() {
        for (String cost : List.of("100", "500", "1000", "2500")) {
            for (String ad : List.of("0", "4", "15")) {
                BigDecimal poison = new BigDecimal(cost);
                BigDecimal rate = new BigDecimal(ad);
                Decision d = EbayProfitPriceService.decide(new BigDecimal("10"), 1, poison, rate,
                        new BigDecimal("50"), RATE, FREIGHT);
                assertThat(d.action()).isEqualTo(Action.RAISE_PRICE);
                assertThat(d.profitAfterStep()).isGreaterThan(new BigDecimal("50"));
                assertThat(EbayProfitCalculator.exactProfit(d.newPrice().subtract(new BigDecimal("0.01")),
                        rate, RATE, poison, FREIGHT)).isLessThanOrEqualTo(new BigDecimal("50"));
            }
        }
    }

    @Test
    void roundUsesTaskFreightRatherThanGlobalConfiguration() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("SKU-A", "offer-A", "AAA-1", "HK")));
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of("L-A", AD));
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(poison("AAA-1", 500)));
        pages(List.of(sku("SKU-A", "L-A", "150", 1)));
        // Global freight=25 would keep $150 at target ¥300; task freight=100 must raise the price.
        service.runSingleRound(7L, 1, new BigDecimal("300"), new BigDecimal("100"), Map.of(), new AtomicBoolean());
        verify(sellClient).bulkUpdatePriceQuantity(argThat(updates -> updates.size() == 1
                && updates.get(0).price().compareTo(new BigDecimal("150")) > 0
                && updates.get(0).availableQuantity() == null));
    }

    @Test
    void rejectsInvalidFreightBeforeCreatingTask() {
        for (BigDecimal freight : List.of(new BigDecimal("-1"), new BigDecimal("100001"))) {
            assertThatThrownBy(() -> service.start(1, BigDecimal.ZERO, freight, List.of()))
                    .hasMessageContaining("运费");
        }
        assertThatThrownBy(() -> service.start(1, BigDecimal.ZERO, null, List.of()))
                .hasMessageContaining("运费");
        verify(taskMapper, never()).insert(any(TaskDO.class));
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
        assertThat(lower.newPrice()).isLessThan(new BigDecimal("149"));
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
        pages(List.of(
                sku("SKU-A", "L-A", "150", 1),
                sku("SKU-B", "L-B", "150", 1),
                sku("SKU-US", "L-US", "150", 1)));
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of("L-A", AD));
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(
                poison("AAA-1", 500), poison("BBB-2", 500)));

        // A：默认目标 100，广告 4% -> 降价；B：Excel 设目标 900 -> 调高至盈利
        service.runSingleRound(7L, 1, new BigDecimal("100"), Map.of("BBB-2", new BigDecimal("900")),
                new AtomicBoolean());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EbaySellApiClient.OfferPriceQuantity>> sent = ArgumentCaptor.forClass(List.class);
        verify(sellClient).bulkUpdatePriceQuantity(sent.capture());
        assertThat(sent.getValue()).hasSize(2);
        EbaySellApiClient.OfferPriceQuantity a = sent.getValue().stream().filter(u -> u.sku().equals("SKU-A"))
                .findFirst().orElseThrow();
        BigDecimal expectedA = EbayProfitPriceService.minimumProfitablePrice(new BigDecimal("500"), AD,
                new BigDecimal("100"), RATE, FREIGHT);
        assertThat(expectedA).isLessThan(new BigDecimal("149"));
        assertThat(a.price()).isEqualByComparingTo(expectedA);
        assertThat(a.availableQuantity()).isNull();
        EbaySellApiClient.OfferPriceQuantity b = sent.getValue().stream().filter(u -> u.sku().equals("SKU-B"))
                .findFirst().orElseThrow();
        assertThat(b.price()).isGreaterThan(new BigDecimal("150"));
        assertThat(b.availableQuantity()).isNull();

        verify(poisonClient).batchQueryPrice(argThat(list -> !list.contains("CCC-3")));
        ArgumentCaptor<TaskItemDO> items = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper, times(2)).insert(items.capture());
        assertThat(items.getAllValues()).extracting(TaskItemDO::getOperateResult)
                .anySatisfy(r -> assertThat(r).startsWith("降价").contains("广告4%"))
                .anySatisfy(r -> assertThat(r).startsWith("调价至盈利").contains("目标¥900"));
        verify(listingMapper).updatePriceAndQuantity("SKU-A", expectedA, 1);
        verify(listingMapper).updatePriceAndQuantity(eq("SKU-B"), argThat(price -> price.compareTo(new BigDecimal("150")) > 0), eq(1));
    }

    @Test
    void abortsRoundWithoutChangesWhenAdRatesUnavailable() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("SKU-A", "offer-A", "AAA-1", "HK")));
        pages(List.of(sku("SKU-A", "L-A", "150", 1)));
        when(marketingClient.getRunningAdRates()).thenThrow(new RuntimeException("403"));

        assertThatThrownBy(() -> service.runSingleRound(7L, 1, BigDecimal.ZERO, Map.of(), new AtomicBoolean()))
                .hasMessageContaining("403");
        verify(tradingClient, never()).forEachActiveSkuPage(any(), any());
        verify(sellClient, never()).bulkUpdatePriceQuantity(anyList());
    }

    @Test
    void processesEachPageBeforeReadingTheNextAndSkipsDuplicatesAcrossPages() {
        when(listingMapper.selectActive()).thenReturn(List.of(
                listing("SKU-A", "offer-A", "AAA-1", "HK"),
                listing("SKU-B", "offer-B", "BBB-2", "HK")));
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of());
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(
                poison("AAA-1", 500), poison("BBB-2", 500)));
        List<String> events = new ArrayList<>();
        when(sellClient.bulkUpdatePriceQuantity(anyList())).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            updates.forEach(u -> events.add("write:" + u.sku()));
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(u.sku(), u.offerId(), 200, null))
                    .toList();
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<EbayTradingApiClient.ActiveSkuPage> consumer = invocation.getArgument(1);
            events.add("page1");
            consumer.accept(new EbayTradingApiClient.ActiveSkuPage(1, 2, List.of(sku("SKU-A", "L-A", "150", 1))));
            events.add("page2");
            // 页边界移动导致 SKU-A 在第 2 页再次出现：一轮内不能降两次价
            consumer.accept(new EbayTradingApiClient.ActiveSkuPage(2, 2, List.of(
                    sku("SKU-A", "L-A", "150", 1), sku("SKU-B", "L-B", "150", 1))));
            return null;
        }).when(tradingClient).forEachActiveSkuPage(any(), any());

        service.runSingleRound(7L, 1, new BigDecimal("100"), Map.of(), new AtomicBoolean());

        assertThat(events).containsExactly("page1", "write:SKU-A", "page2", "write:SKU-B");
        verify(poisonClient, times(2)).batchQueryPrice(anyList());
        verify(taskMapper).updateTaskFailReason(7L, null);
    }

    @Test
    void keepsEarlierPagesWhenALaterPageFails() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("SKU-A", "offer-A", "AAA-1", "HK")));
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of());
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(poison("AAA-1", 500)));
        doAnswer(invocation -> {
            java.util.function.Consumer<EbayTradingApiClient.ActiveSkuPage> consumer = invocation.getArgument(1);
            consumer.accept(new EbayTradingApiClient.ActiveSkuPage(1, 3, List.of(sku("SKU-A", "L-A", "150", 1))));
            throw new RuntimeException("GetSellerList timeout");
        }).when(tradingClient).forEachActiveSkuPage(any(), any());

        assertThatThrownBy(() -> service.runSingleRound(7L, 1, new BigDecimal("100"), Map.of(), new AtomicBoolean()))
                .isInstanceOf(EbayProfitPriceService.RoundInterruptedException.class)
                .hasMessageContaining("第2页").hasMessageContaining("前1页已处理");
        verify(sellClient).bulkUpdatePriceQuantity(anyList());
    }

    @Test
    void calculationFailuresDoNotBlockSamePageOrFollowingPages() {
        when(listingMapper.selectActive()).thenReturn(List.of(
                listing("BAD", "offer-bad", "BAD", "HK"),
                listing("LIMIT", "offer-limit", "LIMIT", "HK"),
                listing("A", "offer-A", "A", "HK"), listing("B", "offer-B", "B", "HK")));
        // With 90% ads, raising cannot reach the target even at the safety limit.
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of("L-bad", new BigDecimal("90"),
                "L-limit", new BigDecimal("90")));
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(
                poison("BAD", 500), poison("LIMIT", 500), poison("A", 500), poison("B", 500)));
        doAnswer(invocation -> {
            java.util.function.Consumer<EbayTradingApiClient.ActiveSkuPage> consumer = invocation.getArgument(1);
            consumer.accept(new EbayTradingApiClient.ActiveSkuPage(1, 2, List.of(
                    sku("BAD", "L-bad", "150", 1), sku("LIMIT", "L-limit", "999999.99", 0),
                    sku("A", "L-A", "150", 1))));
            consumer.accept(new EbayTradingApiClient.ActiveSkuPage(2, 2, List.of(sku("B", "L-B", "150", 1))));
            return null;
        }).when(tradingClient).forEachActiveSkuPage(any(), any());

        service.runSingleRound(7L, 1, new BigDecimal("100"), Map.of(), new AtomicBoolean());

        verify(sellClient, times(2)).bulkUpdatePriceQuantity(argThat(updates -> updates.size() == 1
                && List.of("A", "B").contains(updates.get(0).sku())));
        verify(listingMapper, never()).updatePriceAndQuantity(eq("BAD"), any(), any());
        verify(listingMapper, never()).updatePriceAndQuantity(eq("LIMIT"), any(), any());
        ArgumentCaptor<TaskItemDO> items = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper, times(4)).insert(items.capture());
        assertThat(items.getAllValues()).filteredOn(i -> List.of("BAD", "LIMIT").contains(i.getSku()))
                .hasSize(2).allSatisfy(i -> {
                    assertThat(i.getOperateResult()).contains("失败(计算盈利价").contains("价格和库存未改动");
                    assertThat(i.getTargetPrice()).isNull();
                    assertThat(i.getListingQuantity()).isEqualTo("BAD".equals(i.getSku()) ? 1 : 0);
                });
        verify(taskMapper).updateTaskFailReason(7L, "本轮有 2 个商品处理失败");
        verify(taskMapper, atLeastOnce()).updateTaskAttributes(eq(7L), argThat(json -> {
            var attrs = com.alibaba.fastjson.JSONObject.parseObject(json);
            return attrs.getIntValue("failed") == 2 && attrs.getIntValue("pages") == 2
                    && attrs.getIntValue("lowered") == 2;
        }));
    }

    @Test
    void skipsPageWhenPoisonReturnsNothingInsteadOfZeroingStock() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("SKU-A", "offer-A", "AAA-1", "HK")));
        when(marketingClient.getRunningAdRates()).thenReturn(Map.of());
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of());
        pages(List.of(sku("SKU-A", "L-A", "150", 1)));

        service.runSingleRound(7L, 1, new BigDecimal("100"), Map.of(), new AtomicBoolean());

        verify(sellClient, never()).bulkUpdatePriceQuantity(anyList());
        verify(taskMapper).updateTaskFailReason(eq(7L), contains("得物查价未返回结果"));
    }

    @Test
    void refusesToStartWhileLegacyPriceSyncRunning() {
        when(taskMapper.selectRunningTask("ebay", EbayPriceSyncService.TASK_TYPE, "running"))
                .thenReturn(new TaskDO());
        assertThat(service.start(1, new BigDecimal("50"), FREIGHT, List.of())).isNull();
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

        assertThat(service.start(2, new BigDecimal("50"), new BigDecimal("30"), List.of(row))).isEqualTo(55L);
        ArgumentCaptor<TaskDO> created = ArgumentCaptor.forClass(TaskDO.class);
        verify(taskMapper).insert(created.capture());
        assertThat(com.alibaba.fastjson.JSONObject.parseObject(created.getValue().getParams())
                .getBigDecimal("freight")).isEqualByComparingTo("30");
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

    private void pages(List<EbayTradingApiClient.ActiveSku> skus) {
        doAnswer(invocation -> {
            java.util.function.Consumer<EbayTradingApiClient.ActiveSkuPage> consumer = invocation.getArgument(1);
            consumer.accept(new EbayTradingApiClient.ActiveSkuPage(1, 1, skus));
            return null;
        }).when(tradingClient).forEachActiveSkuPage(any(), any());
    }

    private static EbayTradingApiClient.ActiveSku sku(String sku, String listingId, String price, int quantity) {
        return new EbayTradingApiClient.ActiveSku(sku, listingId, new BigDecimal(price), quantity);
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
