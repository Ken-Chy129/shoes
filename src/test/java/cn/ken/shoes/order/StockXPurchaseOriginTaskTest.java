package cn.ken.shoes.order;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.task.StockXFetchListingsTaskRunner;
import cn.ken.shoes.task.StockXFetchOrdersTaskRunner;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;

class StockXPurchaseOriginTaskTest {
    @Test
    void salesTaskStoresPurchasePriceWithoutReplacingSalePrice() {
        StockXClient client = mock(StockXClient.class);
        StockXAccount account = account();
        TaskItemMapper items = mock(TaskItemMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        when(client.queryOrderListings(StockXOrderCategory.COMPLETED, 1, account)).thenReturn(JSONObject.parseObject("""
                {"edges":[{"node":{"id":"listing-sale","amount":169,"currency":"USD",
                "associatedOrders":{"standardizedSellOrder":{"orderNumber":"02-SALE"},
                "flexIntakeOrder":{"sources":[{"type":"BUY_INTO_FLEX","id":"03-BUY"}]}}}}],
                "pageInfo":{"hasNextPage":false}}
                """));
        when(client.queryPurchasePage(StockXPurchaseOperation.HISTORY, null, account)).thenReturn(history());
        new StockXFetchOrdersTaskRunner(account, 1L, List.of(StockXOrderCategory.COMPLETED),
                client, null, tasks, items).run();
        ArgumentCaptor<TaskItemDO> row = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(items).insert(row.capture());
        assertThat(row.getValue().getPurchaseOrderNumber()).isEqualTo("03-BUY");
        assertThat(row.getValue().getPurchasePrice()).isEqualByComparingTo("141");
        assertThat(row.getValue().getSalePrice()).isEqualByComparingTo("169");
        assertThat(row.getValue().getOrderNumber()).isEqualTo("02-SALE");
    }

    @Test
    void custodialTaskReusesHistoryAcrossListingPages() {
        StockXClient client = mock(StockXClient.class);
        StockXAccount account = account();
        TaskItemMapper items = mock(TaskItemMapper.class);
        when(client.querySellingItemsByInventoryType("CUSTODIAL", 1, account)).thenReturn(JSONObject.parseObject("""
                {"hasMore":true,"items":[{"id":"listing-1","amount":160,"purchaseOrderNumber":"03-BUY"}]}
                """));
        when(client.querySellingItemsByInventoryType("CUSTODIAL", 2, account)).thenReturn(JSONObject.parseObject("""
                {"hasMore":false,"items":[{"id":"listing-2","amount":170,"purchaseOrderNumber":"03-BUY"}]}
                """));
        when(client.queryPurchasePage(StockXPurchaseOperation.HISTORY, null, account)).thenReturn(history());
        new StockXFetchListingsTaskRunner(account, 2L, "CUSTODIAL", client, mock(TaskMapper.class), items).run();
        ArgumentCaptor<TaskItemDO> rows = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(items, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.getPurchaseOrderNumber()).isEqualTo("03-BUY");
            assertThat(row.getPurchasePrice()).isEqualByComparingTo("141");
            assertThat(row.getPurchaseCurrencyCode()).isEqualTo("USD");
        });
        assertThat(rows.getAllValues().getFirst().getCurrentPrice()).isEqualByComparingTo("160");
        verify(client, times(1)).queryPurchasePage(StockXPurchaseOperation.HISTORY, null, account);
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("purchase-origin-test");
        return account;
    }

    private static JSONObject history() {
        return JSONObject.parseObject("""
                {"edges":[{"node":{"orderId":"03-BUY","amount":141,"currencyCode":"USD"}}],
                "pageInfo":{"hasNextPage":false}}
                """);
    }
}
