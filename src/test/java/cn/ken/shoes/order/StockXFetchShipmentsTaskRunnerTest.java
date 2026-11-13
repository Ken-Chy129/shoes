package cn.ken.shoes.order;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.task.StockXFetchOrdersTaskRunner;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockXFetchShipmentsTaskRunnerTest {
    private final StockXClient client = mock(StockXClient.class);
    private final PriceManager prices = mock(PriceManager.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskItemMapper items = mock(TaskItemMapper.class);
    private final StockXAccount account = new StockXAccount();

    StockXFetchShipmentsTaskRunnerTest() {
        account.setName("shipment-runner-test");
    }

    @AfterEach
    void clearState() {
        TaskSwitch.clearFetchOrdersState(account.getName());
        TaskSwitch.clearFetchOrdersState(account.getName() + ":shipments");
    }

    @Test
    void fetchesAllPagesAndTypesWithoutQueryingPricesOrPurchaseHistory() {
        when(client.queryOrderListings(eq(StockXOrderCategory.SHIPMENT_PACKED), anyInt(), eq(account)))
                .thenReturn(page(true, "first"), page(false, "second"));
        when(client.queryOrderListings(StockXOrderCategory.SHIPMENT_SHIPPED, 1, account))
                .thenReturn(page(false, "third"));
        when(client.queryOrderListings(StockXOrderCategory.SHIPMENT_RECEIVED, 1, account))
                .thenReturn(page(false));
        run(List.of(StockXOrderCategory.SHIPMENT_PACKED, StockXOrderCategory.SHIPMENT_SHIPPED,
                StockXOrderCategory.SHIPMENT_RECEIVED));
        ArgumentCaptor<TaskItemDO> saved = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(items, times(3)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(TaskItemDO::getShipmentId)
                .containsExactly("S-first", "S-second", "S-third");
        verify(tasks, times(2)).updateTaskAttributes(eq(71L), contains("\"total\":3"));
        verify(tasks).updateTaskRound(71L, 4);
        verify(tasks).updateTaskStatus(71L, "success");
        verifyNoInteractions(prices);
        verify(client, never()).queryPurchasePage(any(), any(), any());
        verify(client, never()).queryOrderPayout(any(), any());
        assertThat(TaskSwitch.isFetchOrdersRunning(account.getName() + ":shipments")).isFalse();
    }

    @Test
    void rejectsMissingPaginationInsteadOfSilentlyCompleting() {
        JSONObject malformed = page(false, "first");
        malformed.remove("pageInfo");
        failsWith(malformed, "分页");
    }

    @Test
    void rejectsInvalidRowsInsteadOfReportingPartialSuccess() {
        JSONObject malformed = page(false, "first");
        malformed.getJSONArray("edges").getJSONObject(0).remove("node");
        failsWith(malformed, "明细");
    }

    @Test
    void stopsWhenPaginationRepeatsItems() {
        when(client.queryOrderListings(any(), anyInt(), eq(account)))
                .thenReturn(page(true, "same"));
        run(List.of(StockXOrderCategory.SHIPMENT_PACKED));
        verify(client, times(2)).queryOrderListings(any(), anyInt(), eq(account));
        verify(tasks).updateTaskFailed(eq(71L), contains("分页"));
        verify(tasks, never()).updateTaskStatus(71L, "success");
    }

    @Test
    void rejectsEmptyNonTerminalPageAndExpiredCredentials() {
        failsWith(page(true), "分页");
        reset(tasks, client);
        failsWith(new JSONObject().fluentPut("_unauthorized", true), "Token");
    }

    @Test
    void shipmentCancellationIsIndependentFromOrderCancellation() {
        TaskSwitch.cancelFetchOrders(account.getName());
        when(client.queryOrderListings(any(), anyInt(), eq(account))).thenReturn(page(false));
        run(List.of(StockXOrderCategory.SHIPMENT_PACKED));
        verify(tasks).updateTaskStatus(71L, "success");
        reset(tasks, client);
        TaskSwitch.cancelFetchOrders(account.getName() + ":shipments");
        run(List.of(StockXOrderCategory.SHIPMENT_PACKED));
        verify(tasks).updateTaskStatus(71L, "cancel");
        verifyNoInteractions(client);
        assertThat(TaskSwitch.isFetchOrdersCancelled(account.getName())).isTrue();
    }

    private void failsWith(JSONObject response, String reason) {
        when(client.queryOrderListings(any(), anyInt(), eq(account))).thenReturn(response);
        run(List.of(StockXOrderCategory.SHIPMENT_PACKED));
        verify(tasks).updateTaskFailed(eq(71L), contains(reason));
        verify(tasks, never()).updateTaskStatus(71L, "success");
    }

    private void run(List<StockXOrderCategory> categories) {
        new StockXFetchOrdersTaskRunner(account, 71L, categories, client, prices, tasks, items) {
            @Override protected void waitBeforeRetry(long delayMs) { }
        }.run();
    }

    private JSONObject page(boolean more, String... ids) {
        JSONArray edges = new JSONArray();
        for (String id : ids) {
            edges.add(new JSONObject().fluentPut("node", new JSONObject()
                    .fluentPut("id", id)
                    .fluentPut("shipment", new JSONObject().fluentPut("displayId", "S-" + id))));
        }
        return new JSONObject().fluentPut("edges", edges)
                .fluentPut("pageInfo", new JSONObject().fluentPut("hasNextPage", more));
    }
}
