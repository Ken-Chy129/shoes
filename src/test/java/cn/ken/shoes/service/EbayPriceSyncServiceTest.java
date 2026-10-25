package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EbayPriceSyncServiceTest {

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private EbayListingMapper ebayListingMapper;
    private EbaySellApiClient ebayClient;
    private PoisonClient poisonClient;
    private EbayPriceSyncService service;
    private EbayProperties properties;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        ebayListingMapper = mock(EbayListingMapper.class);
        ebayClient = mock(EbaySellApiClient.class);
        poisonClient = mock(PoisonClient.class);
        properties = new EbayProperties();
        properties.setDefaultMarketplaceId("EBAY_US");
        properties.setDefaultCurrency("USD");
        properties.setDefaultContentLanguage("en-US");
        PriceSwitch.EXCHANGE_RATE = 7.3d;
        service = new EbayPriceSyncService(taskMapper, taskItemMapper, ebayListingMapper, ebayClient, poisonClient,
                properties, ignored -> { });
    }

    @Test
    void calculatesPriceAndBulkUpdatesOfferWithoutReadingOffers() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price("DD1391-100", 100)));
        succeedAllBulkUpdates();

        // (100 * 1.1 + 250) / 7.3 = 49.315 -> 49.32
        service.runSingleRound(88L, new BigDecimal("1.1"), new BigDecimal("250"), 3);

        List<EbaySellApiClient.OfferPriceQuantity> sent = singleBulkRequest();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).offerId()).isEqualTo("offer-1");
        assertThat(sent.get(0).sku()).isEqualTo("SKU-1");
        assertThat(sent.get(0).price()).isEqualByComparingTo("49.32");
        assertThat(sent.get(0).currency()).isEqualTo("USD");
        // 有价只改价格，不覆盖 eBay 上随成交变化的库存。
        assertThat(sent.get(0).availableQuantity()).isNull();
        verify(ebayClient, never()).getActiveOffersBySkus(any());
        verify(ebayClient, never()).updateOffer(anyString(), any(), anyString());
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getRound()).isEqualTo(3);
        assertThat(item.getValue().getTargetPrice()).isEqualByComparingTo("49.32");
        assertThat(item.getValue().getOperateResult()).startsWith("改价成功");
        verify(ebayListingMapper).updatePriceAndQuantity("SKU-1", new BigDecimal("49.32"), null);
    }

    @Test
    void refusesToStartWhileProfitPriceTaskRunning() {
        when(taskMapper.selectRunningTask("ebay", EbayProfitPriceService.TASK_TYPE, "running"))
                .thenReturn(new TaskDO());

        assertThat(service.canRun()).isFalse();
        assertThat(service.start(1, new BigDecimal("1.2"), new BigDecimal("320"))).isNull();
        verify(taskMapper, never()).insert(any(TaskDO.class));
    }

    @Test
    void defaultsAdditionTo250WhenNotProvided() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price("DD1391-100", 100)));
        succeedAllBulkUpdates();

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        assertThat(singleBulkRequest().get(0).price()).isEqualByComparingTo("49.32");
    }

    @Test
    void zeroAdditionKeepsPureMultiplierPrice() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price("DD1391-100", 100)));
        succeedAllBulkUpdates();

        service.runSingleRound(88L, new BigDecimal("1.1"), BigDecimal.ZERO, 1);

        assertThat(singleBulkRequest().get(0).price()).isEqualByComparingTo("15.07");
    }

    @Test
    void setsQuantityToZeroWhenSizeHasNoPoisonPrice() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price("OTHER", 100)));
        succeedAllBulkUpdates();

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        EbaySellApiClient.OfferPriceQuantity sent = singleBulkRequest().get(0);
        assertThat(sent.availableQuantity()).isZero();
        assertThat(sent.price()).isNull();
        verify(ebayListingMapper).updatePriceAndQuantity("SKU-1", null, 0);
    }

    @Test
    void sendsOffersInBatchesOf25AndRecordsPerOfferFailures() {
        List<EbayListingDO> mappings = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            EbayListingDO m = mapping();
            m.setSku("SKU-" + i);
            m.setOfferId("offer-" + i);
            mappings.add(m);
        }
        when(ebayListingMapper.selectActive()).thenReturn(mappings);
        when(poisonClient.batchQueryPrice(any())).thenReturn(List.of(price("DD1391-100", 100)));
        when(ebayClient.bulkUpdatePriceQuantity(anyList())).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(u.sku(), u.offerId(),
                    "offer-7".equals(u.offerId()) ? 400 : 200,
                    "offer-7".equals(u.offerId()) ? "HTTP 400: 25713: offer not found" : null)).toList();
        });

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        ArgumentCaptor<List<EbaySellApiClient.OfferPriceQuantity>> requests = ArgumentCaptor.forClass(List.class);
        verify(ebayClient, times(3)).bulkUpdatePriceQuantity(requests.capture());
        assertThat(requests.getAllValues()).extracting(List::size).containsExactlyInAnyOrder(25, 25, 10);
        verify(taskItemMapper, times(60)).insert(any(TaskItemDO.class));
        verify(taskItemMapper).insert(argThat((TaskItemDO item) -> "offer-7".equals(item.getOfferId())
                && item.getOperateResult().startsWith("改价失败") && item.getOperateResult().contains("25713")));
        verify(taskMapper).updateTaskFailReason(88L, "本轮有 1 个商品处理失败");
    }

    @Test
    void wholeBatchFailureOnlyFailsThatBatch() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(any())).thenReturn(List.of(price("DD1391-100", 100)));
        when(ebayClient.bulkUpdatePriceQuantity(anyList()))
                .thenThrow(new cn.ken.shoes.client.EbayApiException("eBay API request failed due to a network error"));

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(taskItemMapper).insert(argThat((TaskItemDO item) ->
                item.getOperateResult().startsWith("改价失败") && item.getOperateResult().contains("network")));
        verify(ebayListingMapper, never()).updatePriceAndQuantity(any(), any(), any());
        verify(taskMapper).updateTaskFailReason(88L, "本轮有 1 个商品处理失败");
    }

    @Test
    void skipsAllUpdatesWhenPoisonReturnsEmpty() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of());

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(ebayClient, never()).bulkUpdatePriceQuantity(any());
        verify(taskItemMapper, never()).insert(any(TaskItemDO.class));
        verify(taskMapper).updateTaskFailReason(eq(88L), contains("跳过"));
    }

    @Test
    void reportsMissingListingMappingsInsteadOfSilentlyFinishingRound() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of());

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(taskMapper).updateTaskFailReason(eq(88L), contains("未找到eBay上架映射"));
        verify(ebayClient, never()).bulkUpdatePriceQuantity(any());
    }

    @Test
    void skipsMappingsWithoutOfferId() {
        EbayListingDO mapping = mapping();
        mapping.setOfferId(null);
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping));

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(poisonClient, never()).batchQueryPrice(any());
        verify(ebayClient, never()).bulkUpdatePriceQuantity(any());
        verify(taskMapper).updateTaskAttributes(eq(88L), contains("\"skipped\":1"));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> service.start(0, new BigDecimal("1.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(1, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(1, new BigDecimal("1.1"), new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationPersistsImmediatelyWhenRuntimeHandleWasLost() {
        service.cancel(88L);

        verify(taskMapper).cancelRunningTask(88L);
        verify(taskMapper, never()).updateTaskStatus(
                88L, TaskDO.TaskStatusEnum.CANCEL.getCode());
    }

    @Test
    void cancellationLetsTheInFlightBatchFinishThenPersistsCancelWithoutInterrupting()
            throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            invocation.<TaskDO>getArgument(0).setId(88L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(any())).thenAnswer(invocation -> {
            requestStarted.countDown();
            try {
                releaseRequest.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return List.of(price("DD1391-100", 100));
        });

        service.start(1, new BigDecimal("1.1"));
        assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
        service.cancel(88L);

        verify(taskMapper, never()).cancelRunningTask(88L);
        verify(taskMapper, never()).updateTaskStatus(
                88L, TaskDO.TaskStatusEnum.CANCEL.getCode());

        releaseRequest.countDown();
        verify(taskMapper, timeout(2_000)).updateTaskStatus(
                88L, TaskDO.TaskStatusEnum.CANCEL.getCode());
        assertThat(interrupted).isFalse();
        verify(ebayClient, never()).bulkUpdatePriceQuantity(any());
    }

    @Test
    void roundFailureWhoseReasonCannotBeSavedDoesNotKillTheLoop() throws Exception {
        doAnswer(invocation -> {
            invocation.<TaskDO>getArgument(0).setId(88L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));
        CountDownLatch roundFailed = new CountDownLatch(1);
        when(ebayListingMapper.selectActive()).thenThrow(new RuntimeException("Communications link failure"));
        doAnswer(invocation -> {
            roundFailed.countDown();
            throw new RuntimeException("Communications link failure");
        }).when(taskMapper).updateTaskFailReason(eq(88L), anyString());

        service.start(1, new BigDecimal("1.1"));
        assertThat(roundFailed.await(2, TimeUnit.SECONDS)).isTrue();
        // 线程没有因为写失败原因异常而退出：仍可被正常取消并写入终态。
        Thread.sleep(100);
        verify(taskMapper, never()).updateTaskStatus(eq(88L), anyString());
        service.cancel(88L);
        verify(taskMapper, timeout(2_000)).updateTaskStatus(
                88L, TaskDO.TaskStatusEnum.CANCEL.getCode());
    }

    @Test
    void onlyRepricesHongKongMappingsAndLeavesUsMappingsUntouched() {
        EbayListingDO hk = mapping();
        EbayListingDO legacy = mapping();
        legacy.setSku("SKU-LEGACY");
        legacy.setOfferId("offer-legacy");
        legacy.setRegion(null);
        EbayListingDO us = mapping();
        us.setSku("SKU-US");
        us.setOfferId("offer-us");
        us.setRegion("us");
        when(ebayListingMapper.selectActive()).thenReturn(List.of(hk, legacy, us));
        when(poisonClient.batchQueryPrice(any())).thenReturn(List.of(price("DD1391-100", 730)));
        succeedAllBulkUpdates();

        service.runSingleRound(77L, new BigDecimal("1.1"), new BigDecimal("250"), 1);

        assertThat(singleBulkRequest()).extracting(EbaySellApiClient.OfferPriceQuantity::offerId)
                .containsExactly("offer-1", "offer-legacy");
        ArgumentCaptor<String> attributes = ArgumentCaptor.forClass(String.class);
        verify(taskMapper, atLeastOnce()).updateTaskAttributes(eq(77L), attributes.capture());
        assertThat(attributes.getValue()).contains("\"skippedUsRegion\":1").contains("\"changed\":2");
    }

    @Test
    void withdrawsTheGroupWhenTheLastInStockSizeLosesItsPoisonPrice() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price("OTHER", 100)));
        when(ebayClient.bulkUpdatePriceQuantity(anyList())).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(u.sku(), u.offerId(), 400,
                    "HTTP 400: 25004: At least one of the variations must have a quantity greater than 0.")).toList();
        });
        when(ebayClient.withdrawOfferByInventoryItemGroup(anyString(), eq("EBAY_US"))).thenReturn(true);

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(ebayClient).withdrawOfferByInventoryItemGroup(
                cn.ken.shoes.service.EbayListingFactory.inventoryGroupKey("DD1391-100",
                        cn.ken.shoes.model.ebay.EbayListingRegion.HK), "EBAY_US");
        verify(ebayListingMapper).markGroupEnded("DD1391-100", "hk");
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getOperateResult()).contains("整组下架");
        verify(taskMapper).updateTaskFailReason(88L, null);
        ArgumentCaptor<String> attributes = ArgumentCaptor.forClass(String.class);
        verify(taskMapper, atLeastOnce()).updateTaskAttributes(eq(88L), attributes.capture());
        assertThat(attributes.getValue()).contains("\"groupWithdrawn\":1").contains("\"failed\":0");
    }

    @Test
    void republishesTheGroupWhenAPricedSizeHasDetachedFromTheListing() {
        when(ebayListingMapper.selectActive()).thenReturn(List.of(mapping()));
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price("DD1391-100", 100)));
        when(ebayClient.bulkUpdatePriceQuantity(anyList())).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(u.sku(), u.offerId(), 400,
                    "HTTP 400: 25013: Invalid data in the Inventory Item Group. Missing name in the variation specifics."))
                    .toList();
        }).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(
                    u.sku(), u.offerId(), 200, null)).toList();
        });

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(ebayClient).publishOfferByInventoryItemGroup(anyString(), eq("EBAY_US"));
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getOperateResult()).startsWith("改价成功").contains("整组重新发布");
    }

    private void succeedAllBulkUpdates() {
        when(ebayClient.bulkUpdatePriceQuantity(anyList())).thenAnswer(invocation -> {
            List<EbaySellApiClient.OfferPriceQuantity> updates = invocation.getArgument(0);
            return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(
                    u.sku(), u.offerId(), 200, null)).toList();
        });
    }

    @SuppressWarnings("unchecked")
    private List<EbaySellApiClient.OfferPriceQuantity> singleBulkRequest() {
        ArgumentCaptor<List<EbaySellApiClient.OfferPriceQuantity>> captor = ArgumentCaptor.forClass(List.class);
        verify(ebayClient).bulkUpdatePriceQuantity(captor.capture());
        return captor.getValue();
    }

    private PoisonPriceDO price(String modelNo, int value) {
        PoisonPriceDO price = new PoisonPriceDO();
        price.setModelNo(modelNo);
        price.setEuSize("42");
        price.setPrice(value);
        return price;
    }

    private EbayListingDO mapping() {
        EbayListingDO mapping = new EbayListingDO();
        mapping.setOfferId("offer-1");
        mapping.setSku("SKU-1");
        mapping.setListingId("listing-1");
        mapping.setStyleId("DD1391-100");
        mapping.setSize("EU42");
        mapping.setEuSize("42");
        mapping.setTitle("Nike Dunk Low");
        mapping.setRegion("hk");
        mapping.setPrice(new BigDecimal("100.00"));
        mapping.setQuantity(1);
        return mapping;
    }
}
