package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import cn.ken.shoes.model.excel.EbayPoisonListingExcel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayPoisonListingServiceTest {

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private PoisonClient poisonClient;
    private EbayBulkListingService bulkListingService;
    private EbayPoisonListingService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        poisonClient = mock(PoisonClient.class);
        bulkListingService = mock(EbayBulkListingService.class);
        EbayProperties properties = new EbayProperties();
        properties.setDefaultMerchantLocationKey("shantou_chenghai");
        properties.setDefaultFulfillmentPolicyId("6246174000");
        properties.setDefaultPaymentPolicyId("6246171000");
        properties.setDefaultReturnPolicyId("6246169000");
        EbayListingFactory factory = new EbayListingFactory(properties, mock(EbayListingTaxonomyService.class));
        doAnswer(invocation -> {
            TaskDO task = invocation.getArgument(0);
            task.setId(7L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));
        service = new EbayPoisonListingService(taskMapper, taskItemMapper, mock(TaskInputSnapshotStore.class),
                poisonClient, bulkListingService, factory, properties, Runnable::run);
    }

    @Test
    void listingPriceIsPoisonPriceTimesOnePointTwoFiveOverSixPointSixPlusFifty() {
        assertThat(EbayPoisonListingService.listingPrice(new BigDecimal("500"))).isEqualByComparingTo("144.70");
        assertThat(EbayPoisonListingService.listingPrice(new BigDecimal("1000"))).isEqualByComparingTo("239.39");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsEveryPricedSizeAtFormulaPriceAndRecordsStylesWithoutPrice() {
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of(
                price("DD1391-100", "42.5", 500),
                price("dd1391-100", "43", 1000),
                price("DD1391-100", "44", 0)));

        Long taskId = service.start(List.of(excel("DD1391-100"), excel(" dd1391-100 "), excel("NOPRICE-1")),
                EbayListingRegion.HK);

        assertThat(taskId).isEqualTo(7L);
        verify(poisonClient).batchQueryPrice(List.of("DD1391-100", "NOPRICE-1"));
        ArgumentCaptor<List<EbayListingExcel>> rows = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Function<EbayListingExcel, BigDecimal>> poison = ArgumentCaptor.forClass(Function.class);
        verify(bulkListingService).run(eq(7L), rows.capture(), any(), eq(EbayListingRegion.HK),
                poison.capture(), any());
        assertThat(rows.getValue()).extracting(EbayListingExcel::getSize).containsExactly("EU42.5", "EU43");
        assertThat(rows.getValue()).extracting(EbayListingExcel::getPrice)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("144.70"), new BigDecimal("239.39"));
        assertThat(rows.getValue()).extracting(EbayListingExcel::getQuantity).containsOnly(1);
        assertThat(poison.getValue().apply(rows.getValue().getFirst())).isEqualByComparingTo("500");
        ArgumentCaptor<TaskItemDO> noPrice = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(noPrice.capture());
        assertThat(noPrice.getValue().getStyleId()).isEqualTo("NOPRICE-1");
        assertThat(noPrice.getValue().getOperateResult()).contains("得物无价");
    }

    @Test
    void failsWithoutListingWhenNoStyleHasAPrice() {
        when(poisonClient.batchQueryPrice(anyList())).thenReturn(List.of());

        service.start(List.of(excel("NOPRICE-1")), EbayListingRegion.HK);

        verify(bulkListingService, never()).run(any(), any(), any(), any(), any(), any());
        verify(taskMapper).updateTaskFailed(eq(7L), org.mockito.ArgumentMatchers.contains("没有价格"));
    }

    @Test
    void rejectsEmptyExcelBeforeCreatingATask() {
        assertThatThrownBy(() -> service.start(List.of(new EbayPoisonListingExcel()), EbayListingRegion.HK))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskMapper, never()).insert(any(TaskDO.class));
    }

    private static EbayPoisonListingExcel excel(String styleId) {
        EbayPoisonListingExcel row = new EbayPoisonListingExcel();
        row.setStyleId(styleId);
        return row;
    }

    private static PoisonPriceDO price(String modelNo, String euSize, int price) {
        PoisonPriceDO value = new PoisonPriceDO();
        value.setModelNo(modelNo);
        value.setEuSize(euSize);
        value.setPrice(price);
        return value;
    }
}
