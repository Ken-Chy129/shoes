package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayDelistServiceTest {

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private EbaySellApiClient ebayClient;
    private EbayDelistService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        ebayClient = mock(EbaySellApiClient.class);
        doAnswer(invocation -> {
            invocation.<TaskDO>getArgument(0).setId(7001L);
            return 1;
        }).when(taskMapper).insert(org.mockito.ArgumentMatchers.any(TaskDO.class));
        service = new EbayDelistService(taskMapper, taskItemMapper, ebayClient,
                new EbayProperties(), Runnable::run);
    }

    @Test
    void withdrawsEveryActiveListingAndRecordsTheOutcome() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(
                mapping("offer-1", "SKU-1", "DD1391-100"),
                mapping("offer-2", "SKU-2", "AH7860-139")));
        ebayHasActiveOffers("SKU-1", "SKU-2");
        when(ebayClient.withdrawOffer("offer-1")).thenReturn(true);
        when(ebayClient.withdrawOffer("offer-2")).thenReturn(true);

        Long taskId = service.start(List.of());

        assertThat(taskId).isEqualTo(7001L);
        verify(ebayClient).withdrawOffer("offer-1");
        verify(ebayClient).withdrawOffer("offer-2");
        verify(taskMapper).updateTaskStatus(7001L, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        ArgumentCaptor<String> attributes = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).updateTaskAttributes(eq(7001L), attributes.capture());
        assertThat(attributes.getValue())
                .contains("\"total\":2")
                .contains("\"delisted\":2")
                .contains("\"failed\":0");
    }

    @Test
    void onlyWithdrawsTheRequestedStyleIds() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(
                mapping("offer-1", "SKU-1", "DD1391-100"),
                mapping("offer-2", "SKU-2", "AH7860-139")));
        ebayHasActiveOffers("SKU-1", "SKU-2");
        when(ebayClient.withdrawOffer("offer-2")).thenReturn(true);

        service.start(List.of("ah7860-139"));

        verify(ebayClient).withdrawOffer("offer-2");
        verify(ebayClient, never()).withdrawOffer("offer-1");
    }

    @Test
    void treatsAnAlreadyEndedListingAsSuccess() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(
                mapping("offer-1", "SKU-1", "DD1391-100")));
        ebayHasActiveOffers("SKU-1");
        when(ebayClient.withdrawOffer("offer-1")).thenReturn(false);

        service.start(List.of());

        verify(taskMapper).updateTaskStatus(7001L, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getOperateResult()).isEqualTo("已下架");
    }

    @Test
    void keepsGoingAndReportsFailuresWhenOneWithdrawFails() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(
                mapping("offer-1", "SKU-1", "DD1391-100"),
                mapping("offer-2", "SKU-2", "AH7860-139")));
        ebayHasActiveOffers("SKU-1", "SKU-2");
        when(ebayClient.withdrawOffer("offer-1"))
                .thenThrow(new EbayApiException("eBay API 500: internal error"));
        when(ebayClient.withdrawOffer("offer-2")).thenReturn(true);

        service.start(List.of());

        verify(ebayClient).withdrawOffer("offer-2");
        verify(taskMapper).updateTaskFailed(eq(7001L), contains("失败 1"));
        verify(taskMapper, never()).updateTaskStatus(
                7001L, TaskDO.TaskStatusEnum.SUCCESS.getCode());
    }

    @Test
    void refusesToStartWhenNothingIsListed() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of());
        when(ebayClient.getInventoryItemSkus()).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到在架的eBay商品");
        verify(taskMapper, never()).insert(org.mockito.ArgumentMatchers.any(TaskDO.class));
    }

    @Test
    void refusesToStartASecondTaskWhileOneIsRunning() {
        TaskDO running = new TaskDO();
        running.setId(6001L);
        when(taskMapper.selectRunningTask("ebay", EbayDelistService.TASK_TYPE,
                TaskDO.TaskStatusEnum.RUNNING.getCode())).thenReturn(running);

        assertThat(service.start(List.of())).isNull();
        assertThat(service.canRun()).isFalse();
        verify(ebayClient, never()).withdrawOffer(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void withdrawsActiveListingsThatHaveNoLocalMapping() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(
                mapping("offer-1", "SKU-1", "DD1391-100")));
        ebayHasActiveOffers("SKU-1", "SKU-ORPHAN");
        when(ebayClient.withdrawOffer("offer-1")).thenReturn(true);
        when(ebayClient.withdrawOffer("offer-orphan")).thenReturn(true);

        service.start(List.of());

        verify(ebayClient).withdrawOffer("offer-1");
        verify(ebayClient).withdrawOffer("offer-orphan");
    }

    @Test
    void skipsOffersThatAreAlreadyEndedOrUnpublished() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of());
        when(ebayClient.getInventoryItemSkus()).thenReturn(List.of("SKU-ENDED"));
        when(ebayClient.getOffersBySku("SKU-ENDED")).thenReturn(List.of(
                new JSONObject(true)
                        .fluentPut("offerId", "offer-ended")
                        .fluentPut("sku", "SKU-ENDED")
                        .fluentPut("status", "UNPUBLISHED")
                        .fluentPut("listing", new JSONObject(true)
                                .fluentPut("listingStatus", "ENDED"))));

        assertThatThrownBy(() -> service.start(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ebayClient, never()).withdrawOffer("offer-ended");
    }

    @Test
    void keepsTheStyleIdAndSizeFromTheLocalMappingInTheAuditTrail() {
        when(taskItemMapper.selectEbayListingMappings()).thenReturn(List.of(
                mapping("offer-1", "SKU-1", "DD1391-100")));
        ebayHasActiveOffers("SKU-1");
        when(ebayClient.withdrawOffer("offer-1")).thenReturn(true);

        service.start(List.of());

        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getStyleId()).isEqualTo("DD1391-100");
        assertThat(item.getValue().getSize()).isEqualTo("USM10");
        assertThat(item.getValue().getOfferId()).isEqualTo("offer-1");
    }

    /**
     * 让 eBay 侧返回这些 SKU 及其在架 offer。
     */
    private void ebayHasActiveOffers(String... skus) {
        when(ebayClient.getInventoryItemSkus()).thenReturn(List.of(skus));
        for (String sku : skus) {
            when(ebayClient.getOffersBySku(sku))
                    .thenReturn(List.of(activeOffer(offerIdFor(sku), sku)));
        }
    }

    private String offerIdFor(String sku) {
        return "offer-" + sku.toLowerCase(java.util.Locale.ROOT).replace("sku-", "");
    }

    private JSONObject activeOffer(String offerId, String sku) {
        return new JSONObject(true)
                .fluentPut("offerId", offerId)
                .fluentPut("sku", sku)
                .fluentPut("status", "PUBLISHED")
                .fluentPut("listing", new JSONObject(true)
                        .fluentPut("listingId", "listing-" + offerId)
                        .fluentPut("listingStatus", "ACTIVE"));
    }

    private TaskItemDO mapping(String offerId, String sku, String styleId) {
        TaskItemDO item = new TaskItemDO();
        item.setOfferId(offerId);
        item.setSku(sku);
        item.setStyleId(styleId);
        item.setListingId("listing-" + offerId);
        item.setSize("USM10");
        item.setTitle("Nike Dunk Low");
        return item;
    }
}
