package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.ebay.EbayListingResult;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayBulkListingServiceTest {

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private TaskInputSnapshotStore snapshotStore;
    private EbayProductMetadataService metadataService;
    private EbayListingService listingService;
    private EbayBulkListingService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        snapshotStore = mock(TaskInputSnapshotStore.class);
        metadataService = mock(EbayProductMetadataService.class);
        listingService = mock(EbayListingService.class);
        EbayProperties properties = new EbayProperties();
        properties.setDefaultMerchantLocationKey("shantou_chenghai");
        properties.setDefaultFulfillmentPolicyId("6246174000");
        properties.setDefaultPaymentPolicyId("6246171000");
        properties.setDefaultReturnPolicyId("6246169000");
        EbayListingFactory factory = new EbayListingFactory(properties);
        doAnswer(invocation -> {
            TaskDO task = invocation.getArgument(0);
            task.setId(88L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));
        doAnswer(invocation -> {
            TaskItemDO item = invocation.getArgument(0);
            item.setId(99L);
            return 1;
        }).when(taskItemMapper).insert(any(TaskItemDO.class));
        service = new EbayBulkListingService(taskMapper, taskItemMapper, snapshotStore,
                metadataService, factory, listingService, Runnable::run);
    }

    @Test
    void publishesEachRowAndRecordsEbayIdentifiers() {
        EbayListingExcel row = row();
        when(metadataService.resolve(row)).thenReturn(metadata());
        when(listingService.publish(any())).thenReturn(
                new EbayListingResult("EBAY-DD1391-100-USM10-NEW", "offer-1", "listing-1", "sandbox"));

        Long taskId = service.start(List.of(row));

        assertThat(taskId).isEqualTo(88L);
        verify(snapshotStore).saveEbayBulkListingInput(88L, List.of(row));
        ArgumentCaptor<TaskItemDO> inserted = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStyleId()).isEqualTo("DD1391-100");
        assertThat(inserted.getValue().getListingQuantity()).isEqualTo(1);
        ArgumentCaptor<TaskItemDO> updated = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).updateById(updated.capture());
        assertThat(updated.getValue().getSku()).isEqualTo("EBAY-DD1391-100-USM10-NEW");
        assertThat(updated.getValue().getOfferId()).isEqualTo("offer-1");
        assertThat(updated.getValue().getListingId()).isEqualTo("listing-1");
        assertThat(updated.getValue().getOperateResult()).isEqualTo("上架成功");
        verify(taskMapper).updateTaskStatus(88L, TaskDO.TaskStatusEnum.SUCCESS.getCode());
    }

    @Test
    void keepsRowFailureReasonAndMarksTheTaskFailed() {
        EbayListingExcel row = row();
        when(metadataService.resolve(row)).thenThrow(new IllegalArgumentException("请在Excel补充标题、描述和图片链接"));

        service.start(List.of(row));

        ArgumentCaptor<TaskItemDO> updated = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).updateById(updated.capture());
        assertThat(updated.getValue().getOperateResult()).contains("上架失败", "请在Excel补充");
        verify(taskMapper).updateTaskFailed(eq(88L), org.mockito.ArgumentMatchers.contains("失败 1"));
    }

    private EbayListingExcel row() {
        EbayListingExcel row = new EbayListingExcel();
        row.setStyleId("DD1391-100");
        row.setSize("USM10");
        row.setQuantity(1);
        row.setPrice(new BigDecimal("129.99"));
        return row;
    }

    private EbayProductMetadata metadata() {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle("Nike Dunk Low Retro");
        metadata.setDescription("Brand new authentic sneakers.");
        metadata.setBrand("Nike");
        metadata.setImageUrls(List.of("https://cdn.example.com/dunk.jpg"));
        return metadata;
    }
}
