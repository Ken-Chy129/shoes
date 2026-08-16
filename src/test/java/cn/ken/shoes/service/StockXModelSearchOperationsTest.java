package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import com.alibaba.fastjson.JSONObject;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingByModelExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXListingCreateItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockXModelSearchOperationsTest {

    @Test
    void searchListingByModelNumberUsesExactLookupOncePerModel() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        PriceManager priceManager = mock(PriceManager.class);
        StockXService service = service(client, itemMapper, taskMapper, priceManager);
        when(client.searchExactItemWithPrice(anyString(), eq("shoes"), eq("US"), any(StockXAccount.class)))
                .thenAnswer(invocation -> {
                    String modelNo = invocation.getArgument(0);
                    StockXPriceExcel item = price(modelNo, "9", "42.5", "variant-" + modelNo);
                    item.setPrice(0);
                    return List.of(item);
                });

        service.searchAndList(account(), 20L, "STYLE-1\nSTYLE-2",
                "featured,lowest_ask", 25, "shoes", 0, true);

        verify(client).searchExactItemWithPrice("STYLE-1", "shoes", "US", account());
        verify(client).searchExactItemWithPrice("STYLE-2", "shoes", "US", account());
        verify(client, never()).searchItemWithPrice(anyString(), anyInt(), anyString(), anyString(),
                anyString(), any(StockXAccount.class));
    }

    @Test
    void keepsAutomaticSearchAsksSubgraphFailurePendingForReconciliation() throws Exception {
        Long taskId = 21L;
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        PriceManager priceManager = mock(PriceManager.class);
        StockXService service = service(client, itemMapper, taskMapper, priceManager);
        StockXPriceExcel item = price("STYLE-1", "9", "42.5", "variant-1");
        item.setPrice(300);
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"), eq("US"), any(StockXAccount.class)))
                .thenReturn(List.of(item));
        when(priceManager.getPoisonPrice("STYLE-1", "42.5")).thenReturn(100);
        when(client.createListingV2(anyList(), any(StockXAccount.class)))
                .thenThrow(new RuntimeException("上架失败:Failed to fetch from Subgraph 'asks'."));
        doAnswer(invocation -> {
            TaskItemDO inserted = invocation.getArgument(0);
            inserted.setId(201L);
            return 1;
        }).when(itemMapper).insert(any(TaskItemDO.class));

        TaskSwitch.cancelSearchVerification(taskId);
        try {
            service.searchAndList(account(), taskId, "STYLE-1",
                    "featured,lowest_ask", 25, "shoes", -1000, true);
        } finally {
            TaskSwitch.resetSearchVerification(taskId);
        }

        ArgumentCaptor<TaskItemDO> update = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).updateById(update.capture());
        assertThat(update.getValue().getOperateResult()).isEqualTo("上架处理中");
    }

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

    @Test
    void keepsAmbiguousAsksSubgraphFailurePendingForReconciliation() throws Exception {
        Long taskId = 15L;
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        StockXService service = service(client, itemMapper, taskMapper, mock(PriceManager.class));
        doAnswer(invocation -> {
            TaskItemDO item = invocation.getArgument(0);
            item.setId(101L);
            return 1;
        }).when(itemMapper).insert(any(TaskItemDO.class));
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class)))
                .thenThrow(new RuntimeException("上架失败:Failed to fetch from Subgraph 'asks'."));

        ModelSearchListingExcel input = new ModelSearchListingExcel();
        input.setVariantId("variant-1");
        input.setTargetPrice(new BigDecimal("321"));
        input.setQuantity(4);
        TaskSwitch.cancelSearchVerification(taskId);
        try {
            service.createModelSearchListings(account(), taskId, List.of(input));
        } finally {
            TaskSwitch.resetSearchVerification(taskId);
        }

        ArgumentCaptor<TaskItemDO> update = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).updateById(update.capture());
        assertThat(update.getValue().getOperateResult()).isEqualTo("上架处理中");
    }

    @Test
    void resolvesVariantFromModelAndSizeBeforeListingSpecifiedPriceAndQuantity() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        PriceManager priceManager = mock(PriceManager.class);
        StockXService service = service(client, itemMapper, taskMapper, priceManager);
        StockXPriceExcel wrongSize = price("DL408-0490/1183C102-751", "5", "38", "variant-5");
        StockXPriceExcel exactSize = price("DL408-0490/1183C102-751", "6", "39", "variant-6");
        when(client.searchExactItemWithPrice(eq("1183C102-751"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(wrongSize, exactSize));
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenReturn("batch-2");
        when(client.verifyListingsByVariantIds(anyList(), any(StockXAccount.class))).thenReturn(Map.of());
        ModelSearchListingByModelExcel input = new ModelSearchListingByModelExcel();
        input.setModelNo("1183C102-751");
        input.setSize("6");
        input.setQuantity(5);
        input.setTargetPrice(new BigDecimal("500"));

        service.createModelSearchListingsByModel(account(), 13L, List.of(input));

        ArgumentCaptor<List<StockXListingCreateItem>> listings = ArgumentCaptor.forClass(List.class);
        verify(client).createListingsWithQuantity(listings.capture(), any(StockXAccount.class));
        assertThat(listings.getValue()).singleElement().satisfies(item -> {
            assertThat(item.variantId()).isEqualTo("variant-6");
            assertThat(item.amount()).isEqualByComparingTo("500");
            assertThat(item.quantity()).isEqualTo(5);
        });
        ArgumentCaptor<TaskItemDO> taskItem = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).insert(taskItem.capture());
        assertThat(taskItem.getValue().getProductId()).isEqualTo("variant-6");
        assertThat(taskItem.getValue().getStyleId()).isEqualTo("DL408-0490/1183C102-751");
        assertThat(taskItem.getValue().getSize()).isEqualTo("6");
        verifyNoInteractions(priceManager);
    }

    @Test
    void listingByModelCachesLookupForMultipleSizesOfTheSameModel() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        StockXService service = service(client, itemMapper, mock(TaskMapper.class), mock(PriceManager.class));
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(
                price("STYLE-1", "6", "39", "variant-6"),
                price("STYLE-1", "7", "40", "variant-7")));
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenReturn("batch-3");

        ModelSearchListingByModelExcel size6 = listingByModel("STYLE-1", "6", "500", 2);
        ModelSearchListingByModelExcel size7 = listingByModel("STYLE-1", "7", "510", 3);

        service.createModelSearchListingsByModel(account(), 14L, List.of(size6, size7));

        verify(client, times(1)).searchExactItemWithPrice("STYLE-1", "shoes", "US", account());
        ArgumentCaptor<List<StockXListingCreateItem>> listings = ArgumentCaptor.forClass(List.class);
        verify(client).createListingsWithQuantity(listings.capture(), any(StockXAccount.class));
        assertThat(listings.getValue()).extracting(StockXListingCreateItem::variantId)
                .containsExactly("variant-6", "variant-7");
    }

    @Test
    void listingByModelMergesDuplicateModelAndSizeQuantitiesBeforeSubmitting() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        StockXService service = service(client, itemMapper, mock(TaskMapper.class), mock(PriceManager.class));
        when(client.searchExactItemWithPrice(eq("1183C102-751"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(
                price("1183C102-751", "9.5", "43.5", "variant-9.5")));
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenReturn("batch-4");

        ModelSearchListingByModelExcel first = listingByModel("1183C102-751", "9.5", "500", 1);
        ModelSearchListingByModelExcel second = listingByModel("1183C102-751", "US 9.5", "500", 2);
        ModelSearchListingByModelExcel third = listingByModel("1183C102-751", "EU 43.5", "500", 3);

        service.createModelSearchListingsByModel(account(), 16L, List.of(first, second, third));

        ArgumentCaptor<List<StockXListingCreateItem>> listings = ArgumentCaptor.forClass(List.class);
        verify(client).createListingsWithQuantity(listings.capture(), any(StockXAccount.class));
        assertThat(listings.getValue()).singleElement().satisfies(item -> {
            assertThat(item.variantId()).isEqualTo("variant-9.5");
            assertThat(item.amount()).isEqualByComparingTo("500");
            assertThat(item.quantity()).isEqualTo(6);
        });

        ArgumentCaptor<TaskItemDO> taskItem = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).insert(taskItem.capture());
        assertThat(taskItem.getValue().getProductId()).isEqualTo("variant-9.5");
        assertThat(taskItem.getValue().getListingQuantity()).isEqualTo(6);
        assertThat(taskItem.getValue().getOperateResult()).isEqualTo("待上架");
    }

    @Test
    void listingByModelRejectsConflictingPricesForTheSameResolvedVariant() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        StockXService service = service(client, itemMapper, mock(TaskMapper.class), mock(PriceManager.class));
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(
                price("STYLE-1", "9", "42.5", "variant-9")));

        ModelSearchListingByModelExcel first = listingByModel("STYLE-1", "9", "500", 2);
        ModelSearchListingByModelExcel second = listingByModel("STYLE-1", "EU 42.5", "510", 3);

        service.createModelSearchListingsByModel(account(), 17L, List.of(first, second));

        verify(client, never()).createListingsWithQuantity(anyList(), any(StockXAccount.class));
        ArgumentCaptor<TaskItemDO> taskItem = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper).insert(taskItem.capture());
        assertThat(taskItem.getValue().getProductId()).isEqualTo("variant-9");
        assertThat(taskItem.getValue().getListingQuantity()).isEqualTo(5);
        assertThat(taskItem.getValue().getOperateResult())
                .isEqualTo("上架失败-相同货号尺码的上架价格不一致");
    }

    @Test
    void listingByModelMergesDuplicatesAcrossThePreviousFiftyItemBatchBoundary() throws Exception {
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        StockXService service = service(client, itemMapper, mock(TaskMapper.class), mock(PriceManager.class));
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(
                price("STYLE-1", "9", "42.5", "variant-9")));
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenReturn("batch-5");

        List<ModelSearchListingByModelExcel> rows = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            rows.add(listingByModel("STYLE-1", "9", "500", 1));
        }

        service.createModelSearchListingsByModel(account(), 18L, rows);

        ArgumentCaptor<List<StockXListingCreateItem>> listings = ArgumentCaptor.forClass(List.class);
        verify(client).createListingsWithQuantity(listings.capture(), any(StockXAccount.class));
        assertThat(listings.getValue()).singleElement()
                .extracting(StockXListingCreateItem::quantity)
                .isEqualTo(51);
        verify(itemMapper, times(1)).insert(any(TaskItemDO.class));
    }

    @Test
    void listingByModelResumeReusesExistingPendingTaskItem() throws Exception {
        Long taskId = 19L;
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        StockXService service = service(client, itemMapper, mock(TaskMapper.class), mock(PriceManager.class));
        StockXPriceExcel matched = price("STYLE-1", "9", "42.5", "variant-9");
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(matched));
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenReturn("batch-resume");
        TaskItemDO pending = new TaskItemDO();
        pending.setId(901L);
        pending.setTaskId(taskId);
        pending.setProductId("variant-9");
        pending.setOperateResult("待上架");
        pending.setListingQuantity(3);
        when(itemMapper.selectByCondition(taskId, null, "待上架", null, null, 0, Integer.MAX_VALUE))
                .thenReturn(List.of(pending));

        TaskSwitch.cancelSearchVerification(taskId);
        try {
            service.createModelSearchListingsByModel(account(), taskId,
                    List.of(listingByModel("STYLE-1", "9", "500", 3)));
        } finally {
            TaskSwitch.resetSearchVerification(taskId);
        }

        verify(itemMapper, never()).insert(any(TaskItemDO.class));
        ArgumentCaptor<TaskItemDO> updates = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(itemMapper, times(2)).updateById(updates.capture());
        assertThat(updates.getAllValues().get(0)).satisfies(update -> {
            assertThat(update.getId()).isEqualTo(901L);
            assertThat(update.getListingQuantity()).isEqualTo(3);
            assertThat(update.getTargetPrice()).isEqualByComparingTo("500");
        });
        assertThat(updates.getAllValues().get(1)).satisfies(update -> {
            assertThat(update.getId()).isEqualTo(901L);
            assertThat(update.getOperateResult()).isEqualTo("上架处理中");
        });
    }

    @Test
    void listingByModelReportsOneHundredPercentOnlyAfterSubmissionCompletes() throws Exception {
        Long taskId = 20L;
        StockXClient client = mock(StockXClient.class);
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        StockXService service = service(client, itemMapper, taskMapper, mock(PriceManager.class));
        when(client.searchExactItemWithPrice(eq("STYLE-1"), eq("shoes"),
                eq("US"), any(StockXAccount.class))).thenReturn(List.of(
                price("STYLE-1", "9", "42.5", "variant-9")));
        List<Integer> progressValues = new ArrayList<>();
        doAnswer(invocation -> {
            JSONObject attributes = JSONObject.parseObject(invocation.getArgument(1));
            progressValues.add(attributes.getIntValue("progress"));
            return null;
        }).when(taskMapper).updateTaskAttributes(eq(taskId), anyString());
        when(client.createListingsWithQuantity(anyList(), any(StockXAccount.class))).thenAnswer(invocation -> {
            assertThat(progressValues).isNotEmpty();
            assertThat(progressValues.get(progressValues.size() - 1)).isLessThan(100);
            return "batch-progress";
        });

        TaskSwitch.cancelSearchVerification(taskId);
        try {
            service.createModelSearchListingsByModel(account(), taskId,
                    List.of(listingByModel("STYLE-1", "9", "500", 1)));
        } finally {
            TaskSwitch.resetSearchVerification(taskId);
        }

        assertThat(progressValues).isNotEmpty();
        assertThat(progressValues.get(progressValues.size() - 1)).isEqualTo(100);
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

    private static ModelSearchListingByModelExcel listingByModel(String modelNo, String size,
                                                                 String price, int quantity) {
        ModelSearchListingByModelExcel input = new ModelSearchListingByModelExcel();
        input.setModelNo(modelNo);
        input.setSize(size);
        input.setTargetPrice(new BigDecimal(price));
        input.setQuantity(quantity);
        return input;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = StockXService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
