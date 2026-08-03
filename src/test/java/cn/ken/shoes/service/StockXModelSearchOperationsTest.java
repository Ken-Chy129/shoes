package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXListingCreateItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockXModelSearchOperationsTest {

    @Test
    void fetchesStandardAndFlexPricesForExactModelAndSize() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        StockXService service = service(client, itemMapper, taskMapper, mock(PriceManager.class));
        StockXPriceExcel result = new StockXPriceExcel();
        result.setId("variant-1");
        result.setModelNo("STYLE-1");
        result.setUsmSize("9");
        result.setEuSize("42.5");
        result.setStandardPrice(300);
        result.setFlexPrice(315);
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(result));

        ModelNoSearchExcel input = new ModelNoSearchExcel();
        input.setModelNo("STYLE-1");
        input.setSize("EU 42.5");
        service.fetchModelSearchPrices(account(), 10L, List.of(input));

        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).insert(item.capture());
        assertThat(item.getValue().getProductId()).isEqualTo("variant-1");
        assertThat(item.getValue().getLowestPrice()).isEqualByComparingTo("300");
        assertThat(item.getValue().getFlexLowestPrice()).isEqualByComparingTo("315");
        assertThat(item.getValue().getOperateResult()).isEqualTo("获取成功");
        verify(client, never()).searchItemWithPrice(anyString(), anyInt(), anyString(), anyString(),
                anyString(), any(StockXAccount.class));
    }

    @Test
    void keepsSpecifiedSizeWhenStockXReturnsOneAliasFromCombinedModelNumber() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        StockXService service = service(client, itemMapper, mock(TaskMapper.class), mock(PriceManager.class));
        StockXPriceExcel wrongSize = price("STYLE-1", "8", "41", "variant-8");
        StockXPriceExcel exactSize = price("STYLE-1", "9", "42.5", "variant-9");
        when(client.searchExactItemWithPrice(eq("ALIAS-1 / STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(wrongSize, exactSize));

        ModelNoSearchExcel input = new ModelNoSearchExcel();
        input.setModelNo("ALIAS-1 / STYLE-1");
        input.setSize("EU 42.5");
        service.fetchModelSearchPrices(account(), 12L, List.of(input));

        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).insert(item.capture());
        assertThat(item.getValue().getProductId()).isEqualTo("variant-9");
        assertThat(item.getValue().getSize()).isEqualTo("9");
    }

    @Test
    void listsSpecifiedVariantPriceAndQuantityWithoutProfitComparison() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        PriceManager priceManager = mock(PriceManager.class);
        StockXService service = service(client, itemMapper, taskMapper, priceManager);
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenReturn("batch-1");
        when(client.verifyListingsByVariantIds(anyList(), any(StockXAccount.class))).thenReturn(Map.of());

        ModelSearchListingExcel input = new ModelSearchListingExcel();
        input.setVariantId("variant-1");
        input.setTargetPrice(new BigDecimal("321"));
        input.setQuantity(4);
        service.createModelSearchListings(account(), 11L, List.of(input));

        ArgumentCaptor<List<StockXListingCreateItem>> items = ArgumentCaptor.forClass(List.class);
        verify(client).createListingsWithQuantity(items.capture(), any(StockXAccount.class));
        assertThat(items.getValue()).singleElement().satisfies(item -> {
            assertThat(item.variantId()).isEqualTo("variant-1");
            assertThat(item.amount()).isEqualByComparingTo("321");
            assertThat(item.quantity()).isEqualTo(4);
        });
        verifyNoInteractions(priceManager);
    }

    private static StockXService service(StockXClient client, TaskItemMapper itemMapper,
                                         TaskMapper taskMapper, PriceManager priceManager) throws Exception {
        StockXService service = new StockXService();
        setField(service, "stockXClient", client);
        setField(service, "taskItemMapper", itemMapper);
        setField(service, "taskMapper", taskMapper);
        setField(service, "priceManager", priceManager);
        return service;
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("account-1");
        account.setCountry("US");
        return account;
    }

    private static StockXPriceExcel price(String modelNo, String usSize, String euSize, String variantId) {
        StockXPriceExcel result = new StockXPriceExcel();
        result.setModelNo(modelNo);
        result.setUsmSize(usSize);
        result.setEuSize(euSize);
        result.setId(variantId);
        result.setStandardPrice(300);
        return result;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = StockXService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
