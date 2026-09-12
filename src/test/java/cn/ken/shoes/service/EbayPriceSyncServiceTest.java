package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
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
    private EbaySellApiClient ebayClient;
    private PoisonClient poisonClient;
    private EbayPriceSyncService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        ebayClient = mock(EbaySellApiClient.class);
        poisonClient = mock(PoisonClient.class);
        EbayProperties properties = new EbayProperties();
        properties.setDefaultMarketplaceId("EBAY_US");
        properties.setDefaultCurrency("USD");
        properties.setDefaultContentLanguage("en-US");
        PriceSwitch.EXCHANGE_RATE = 7.3d;
        service = new EbayPriceSyncService(taskMapper, taskItemMapper, ebayClient, poisonClient, properties);
    }

    @Test
    void calculatesPriceAndUpdatesOffer() {
        TaskItemDO mapping = mapping();
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(mapping));
        when(ebayClient.getActiveOffersBySkus(java.util.Set.of("SKU-1"))).thenReturn(List.of(offer("offer-1", "SKU-1", "100.00", 1)));
        PoisonPriceDO price = new PoisonPriceDO();
        price.setModelNo("DD1391-100");
        price.setEuSize("42");
        price.setPrice(100);
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price));

        // (100 * 1.1 + 250) / 7.3 = 49.315 -> 49.32
        service.runSingleRound(88L, new BigDecimal("1.1"), new BigDecimal("250"), 3);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(ebayClient).updateOffer(eq("offer-1"), payload.capture(), eq("en-US"));
        assertThat(payload.getValue().getJSONObject("pricingSummary")
                .getJSONObject("price").getString("value")).isEqualTo("49.32");
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getRound()).isEqualTo(3);
        assertThat(item.getValue().getTargetPrice()).isEqualByComparingTo("49.32");
        assertThat(item.getValue().getOperateResult()).startsWith("改价成功");
    }

    @Test
    void defaultsAdditionTo250WhenNotProvided() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(mapping()));
        when(ebayClient.getActiveOffersBySkus(java.util.Set.of("SKU-1"))).thenReturn(List.of(offer("offer-1", "SKU-1", "100.00", 1)));
        PoisonPriceDO price = new PoisonPriceDO();
        price.setModelNo("DD1391-100");
        price.setEuSize("42");
        price.setPrice(100);
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price));

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(ebayClient).updateOffer(eq("offer-1"), payload.capture(), eq("en-US"));
        assertThat(payload.getValue().getJSONObject("pricingSummary")
                .getJSONObject("price").getString("value")).isEqualTo("49.32");
    }

    @Test
    void zeroAdditionKeepsPureMultiplierPrice() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(mapping()));
        when(ebayClient.getActiveOffersBySkus(java.util.Set.of("SKU-1"))).thenReturn(List.of(offer("offer-1", "SKU-1", "100.00", 1)));
        PoisonPriceDO price = new PoisonPriceDO();
        price.setModelNo("DD1391-100");
        price.setEuSize("42");
        price.setPrice(100);
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(price));

        service.runSingleRound(88L, new BigDecimal("1.1"), BigDecimal.ZERO, 1);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(ebayClient).updateOffer(eq("offer-1"), payload.capture(), eq("en-US"));
        assertThat(payload.getValue().getJSONObject("pricingSummary")
                .getJSONObject("price").getString("value")).isEqualTo("15.07");
    }

    @Test
    void setsQuantityToZeroWhenSizeHasNoPoisonPrice() {
        TaskItemDO mapping = mapping();
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(mapping));
        when(ebayClient.getActiveOffersBySkus(java.util.Set.of("SKU-1"))).thenReturn(List.of(offer("offer-1", "SKU-1", "100.00", 2)));
        PoisonPriceDO other = new PoisonPriceDO();
        other.setModelNo("OTHER");
        other.setEuSize("42");
        other.setPrice(100);
        when(poisonClient.batchQueryPrice(List.of("DD1391-100"))).thenReturn(List.of(other));

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(ebayClient).updateOffer(eq("offer-1"), payload.capture(), eq("en-US"));
        assertThat(payload.getValue().getInteger("availableQuantity")).isZero();
        assertThat(payload.getValue().getJSONObject("pricingSummary")
                .getJSONObject("price").getString("value")).isEqualTo("100.00");
    }

    @Test
    void skipsAllUpdatesWhenPoisonReturnsEmpty() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(mapping()));
        when(ebayClient.getActiveOffersBySkus(java.util.Set.of("SKU-1"))).thenReturn(List.of(offer("offer-1", "SKU-1", "100.00", 1)));
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of());

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        verify(ebayClient, never()).updateOffer(anyString(), any(), anyString());
        verify(taskItemMapper, never()).insert(any(TaskItemDO.class));
        verify(taskMapper).updateTaskFailReason(eq(88L), contains("跳过"));
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
    void cancellationOfAnActiveTaskWaitsForTheWorkerToStopBeforePersistingTheTerminalState()
            throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<TaskDO>getArgument(0).setId(88L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(mapping()));
        when(ebayClient.getActiveOffersBySkus(anyCollection())).thenAnswer(invocation -> {
            requestStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseRequest.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 模拟一次不能被线程中断强制回滚的在途 HTTP 请求。
                }
            }
            return List.of();
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
    }

    private TaskItemDO mapping() {
        TaskItemDO mapping = new TaskItemDO();
        mapping.setOfferId("offer-1");
        mapping.setSku("SKU-1");
        mapping.setListingId("listing-1");
        mapping.setStyleId("DD1391-100");
        mapping.setSize("EU42");
        mapping.setEuSize("42");
        mapping.setTitle("Nike Dunk Low");
        return mapping;
    }

    @Test
    void readsOffersByMappedSkuBecauseEbayRejectsAccountWideOfferListing() {
        TaskItemDO first = mapping();
        TaskItemDO second = mapping();
        second.setSku("SKU-2");
        second.setOfferId("offer-2");
        when(taskItemMapper.selectEbayListingMappings())
                .thenReturn(List.of(first, second));
        when(ebayClient.getActiveOffersBySkus(anyCollection())).thenReturn(List.of());

        service.runSingleRound(88L, new BigDecimal("1.1"), 1);

        ArgumentCaptor<java.util.Collection<String>> skus =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(ebayClient).getActiveOffersBySkus(skus.capture());
        assertThat(skus.getValue()).containsExactly("SKU-1", "SKU-2");
    }

    private JSONObject offer(String offerId, String sku, String price, int quantity) {
        return new JSONObject(true)
                .fluentPut("offerId", offerId)
                .fluentPut("sku", sku)
                .fluentPut("availableQuantity", quantity)
                .fluentPut("pricingSummary", new JSONObject(true)
                        .fluentPut("price", new JSONObject(true)
                                .fluentPut("currency", "USD").fluentPut("value", price)))
                .fluentPut("listingPolicies", new JSONObject(true)
                        .fluentPut("fulfillmentPolicyId", "fulfill"));
    }
}
