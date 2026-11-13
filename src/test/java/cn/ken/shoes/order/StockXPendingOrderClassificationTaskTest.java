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
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static cn.ken.shoes.order.StockXPendingOrderClassifierTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockXPendingOrderClassificationTaskTest {
    private final StockXClient client = mock(StockXClient.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskItemMapper items = mock(TaskItemMapper.class);
    private final StockXAccount account = new StockXAccount();

    StockXPendingOrderClassificationTaskTest() {
        account.setName("pending-classification-test");
        account.setCountry("US");
        when(client.queryPendingAsks(null, account)).thenReturn(JSONObject.parseObject("""
                {"edges":[{"node":{"id":"ask-1","amount":200,"currentCurrency":"USD",
                  "soldOn":"2026-07-16T03:04:05Z","productVariant":{"id":"variant-1",
                    "product":{"styleId":"STYLE-1"}}}}],
                  "pageInfo":{"hasNextPage":false}}
                """));
    }

    @Test
    void retriesAndPersistsNormalOrderWithoutOverwritingExtensionStatus() {
        when(client.queryVariantSalesPage("variant-1", "USD", null, account)).thenReturn(null,
                page(1, null, sale("200", "2026-07-16T03:04:05Z")));
        run();
        assertThat(saved().getReplenishmentOrder()).isFalse();
        assertThat(saved().getOperateResult()).isEqualTo("未延期");
        verify(client, times(2)).queryVariantSalesPage("variant-1", "USD", null, account);
        verify(tasks).updateTaskStatus(81L, "success");
    }

    @Test
    void failedLookupRemainsUnknownAndDoesNotDiscardOrder() {
        when(client.queryVariantSalesPage("variant-1", "USD", null, account)).thenReturn(null);
        run();
        assertThat(saved().getReplenishmentOrder()).isNull();
        verify(client, times(3)).queryVariantSalesPage("variant-1", "USD", null, account);
        verify(tasks).updateTaskStatus(81L, "success");
        verify(tasks, never()).updateTaskFailed(anyLong(), anyString());
    }

    @Test
    void cancellationDuringHistoryQueryDoesNotSaveOrMisclassifyOrder() {
        when(client.queryVariantSalesPage("variant-1", "USD", null, account)).thenAnswer(invocation -> {
            TaskSwitch.cancelFetchOrders(account.getName());
            return page(0, null);
        });
        run();
        verifyNoInteractions(items);
        verify(tasks).updateTaskStatus(81L, "cancel");
        assertThat(TaskSwitch.isFetchOrdersCancelled(account.getName())).isFalse();
    }

    private TaskItemDO saved() {
        ArgumentCaptor<TaskItemDO> saved = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(items).insert(saved.capture());
        return saved.getValue();
    }

    private void run() {
        new StockXFetchOrdersTaskRunner(account, 81L, List.of(StockXOrderCategory.PENDING),
                client, mock(PriceManager.class), tasks, items) {
            @Override
            protected void waitBeforeRetry(long delayMs) {}
        }.run();
    }
}
