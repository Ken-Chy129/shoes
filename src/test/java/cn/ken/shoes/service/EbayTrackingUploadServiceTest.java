package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayTrackingExcel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayTrackingUploadServiceTest {

    private TaskMapper taskMapper;
    private TaskItemMapper taskItemMapper;
    private TaskInputSnapshotStore snapshotStore;
    private EbayTradingApiClient tradingClient;
    private EbayTrackingUploadService service;
    private final List<Runnable> scheduled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskItemMapper = mock(TaskItemMapper.class);
        snapshotStore = mock(TaskInputSnapshotStore.class);
        tradingClient = mock(EbayTradingApiClient.class);
        doAnswer(invocation -> {
            invocation.<TaskDO>getArgument(0).setId(88L);
            return 1;
        }).when(taskMapper).insert(any(TaskDO.class));
        service = new EbayTrackingUploadService(taskMapper, taskItemMapper, snapshotStore, tradingClient,
                new EbayProperties(), scheduled::add);
    }

    @Test
    void addsTrackingToTheOrderWithFedExByDefault() {
        when(tradingClient.getOrdersByIds(anyList())).thenReturn(List.of(order("21-15259-42163", "Active", null)));

        Long taskId = service.start(List.of(row("21-15259-42163", "524382226900", null)));
        scheduled.forEach(Runnable::run);

        assertThat(taskId).isEqualTo(88L);
        verify(snapshotStore).saveEbayTrackingInput(eq(88L), anyList());
        verify(tradingClient).addTracking("21-15259-42163",
                List.of(new EbayTradingApiClient.ShipmentTracking("524382226900", "FedEx")));
        ArgumentCaptor<TaskItemDO> item = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper).insert(item.capture());
        assertThat(item.getValue().getOrderNumber()).isEqualTo("21-15259-42163");
        assertThat(item.getValue().getTrackingNumber()).isEqualTo("524382226900");
        assertThat(item.getValue().getShippingCarrier()).isEqualTo("FedEx");
        assertThat(item.getValue().getListingId()).isEqualTo("407265753713");
        assertThat(item.getValue().getTitle()).isEqualTo("Li-Ning Gamma 2 'Muticolor' ABAV033-13");
        assertThat(item.getValue().getOperateResult()).isEqualTo("添加成功");
        verify(taskMapper).updateTaskStatus(88L, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(lastAttributes()).containsEntry("succeeded", 1).containsEntry("failed", 0);
    }

    @Test
    void groupsRowsOfTheSameOrderAndSkipsExistingTrackingNumbers() {
        when(tradingClient.getOrdersByIds(anyList()))
                .thenReturn(List.of(order("1-1-1", "Completed", "AAA111"), order("2-2-2", "Completed", "BBB222")));

        service.start(List.of(
                row("1-1-1", "AAA111", "FedEx"),
                row("1-1-1", "CCC333", "ups"),
                row("1-1-1", "ccc333", "UPS"),
                row("2-2-2", "bbb222", null)));
        scheduled.forEach(Runnable::run);

        verify(tradingClient).addTracking("1-1-1",
                List.of(new EbayTradingApiClient.ShipmentTracking("CCC333", "UPS")));
        verify(tradingClient, never()).addTracking(eq("2-2-2"), anyList());
        verify(taskItemMapper, times(3)).insert(any(TaskItemDO.class));
        assertThat(lastAttributes()).containsEntry("total", 2).containsEntry("succeeded", 1)
                .containsEntry("skipped", 1);
    }

    @Test
    void reportsMissingCancelledAndRejectedOrdersWithoutStopping() {
        when(tradingClient.getOrdersByIds(anyList()))
                .thenReturn(List.of(order("2-2-2", "Cancelled", null), order("3-3-3", "Active", null)));
        doThrow(new EbayApiException("eBay CompleteSale失败：21916 Invalid"))
                .when(tradingClient).addTracking(eq("3-3-3"), anyList());

        service.start(List.of(row("1-1-1", "T0001", null), row("2-2-2", "T0002", null),
                row("3-3-3", "T0003", null)));
        scheduled.forEach(Runnable::run);

        ArgumentCaptor<TaskItemDO> items = ArgumentCaptor.forClass(TaskItemDO.class);
        verify(taskItemMapper, times(3)).insert(items.capture());
        assertThat(items.getAllValues()).extracting(TaskItemDO::getOperateResult)
                .containsExactly("失败：eBay未找到该订单，请检查订单编号", "失败：订单已取消",
                        "失败：eBay CompleteSale失败：21916 Invalid");
        verify(taskMapper).updateTaskStatus(88L, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        assertThat(lastAttributes()).containsEntry("failed", 3);
    }

    @Test
    void stillSubmitsWhenTheOrderLookupFails() {
        when(tradingClient.getOrdersByIds(anyList())).thenThrow(new EbayApiException("timeout"));

        service.start(List.of(row("1-1-1", "T0001", null)));
        scheduled.forEach(Runnable::run);

        verify(tradingClient).addTracking("1-1-1",
                List.of(new EbayTradingApiClient.ShipmentTracking("T0001", "FedEx")));
    }

    @Test
    void rejectsInvalidRowsWithRowNumbersBeforeCreatingTheTask() {
        assertThatThrownBy(() -> service.start(List.of(row("21-15259-42163", "524382226900", null),
                row("21-15259-42163", null, null), row("21/152", "1234", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第3行追踪号码无效")
                .hasMessageContaining("第4行订单编号无效");
        assertThatThrownBy(() -> service.start(List.of(row(null, null, null))))
                .hasMessageContaining("没有有效");
        verify(taskMapper, never()).insert(any(TaskDO.class));
    }

    @Test
    void normalizesExcelNumberFormats() {
        List<EbayTrackingExcel> rows = EbayTrackingUploadService.normalize(List.of(
                row(" 21-15259-42163 ", "5.243822269E11", " fedex "),
                row("1-1-1", "524382226900.0", "联邦"),
                row("1-1-1", "'1Z 999", "Royal Mail")));

        assertThat(rows).extracting(EbayTrackingExcel::getOrderNumber).containsExactly("21-15259-42163", "1-1-1", "1-1-1");
        assertThat(rows).extracting(EbayTrackingExcel::getTrackingNumber)
                .containsExactly("524382226900", "524382226900", "1Z999");
        assertThat(rows).extracting(EbayTrackingExcel::getCarrier).containsExactly("FedEx", "FedEx", "Royal Mail");
    }

    @Test
    void refusesToStartWhileAnotherTrackingTaskIsRunning() {
        when(taskMapper.selectRunningTask(anyString(), eq(EbayTrackingUploadService.TASK_TYPE), anyString()))
                .thenReturn(new TaskDO());

        assertThat(service.start(List.of(row("1-1-1", "T0001", null)))).isNull();
        verify(taskMapper, never()).insert(any(TaskDO.class));
    }

    private JSONObject lastAttributes() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper, org.mockito.Mockito.atLeastOnce()).updateTaskAttributes(eq(88L), captor.capture());
        return JSONObject.parseObject(captor.getValue());
    }

    private static EbayTrackingExcel row(String orderId, String tracking, String carrier) {
        EbayTrackingExcel row = new EbayTrackingExcel();
        row.setOrderNumber(orderId);
        row.setTrackingNumber(tracking);
        row.setCarrier(carrier);
        return row;
    }

    private static Element order(String orderId, String status, String existingTracking) {
        String tracking = existingTracking == null ? "" : """
                <ShippingDetails><ShipmentTrackingDetails>
                  <ShippingCarrierUsed>FedEx</ShippingCarrierUsed>
                  <ShipmentTrackingNumber>%s</ShipmentTrackingNumber>
                </ShipmentTrackingDetails></ShippingDetails>""".formatted(existingTracking);
        String xml = """
                <Order xmlns="urn:ebay:apis:eBLBaseComponents">
                  <OrderID>%s</OrderID>
                  <OrderStatus>%s</OrderStatus>
                  <BuyerUserID>rhenlemuel</BuyerUserID>
                  <TransactionArray><Transaction>
                    <Item><ItemID>407265753713</ItemID><Title>Li-Ning Gamma 2 'Muticolor' ABAV033-13</Title></Item>
                    %s
                  </Transaction></TransactionArray>
                </Order>
                """.formatted(orderId, status, tracking);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
