package cn.ken.shoes.controller;

import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXPurchaseGuidanceExcel;
import cn.ken.shoes.model.excel.StockXPurchaseOrderExcel;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskItemControllerStockXExportTest {

    @Test
    void exportsPurchaseHistoryWithPurchasePrice() throws Exception {
        TaskDO task = new TaskDO();
        task.setId(91L);
        task.setPlatform("stockx");
        task.setTaskType("purchase");
        task.setParams("{\"operation\":\"history\"}");
        TaskItemDO item = new TaskItemDO();
        item.setOrderNumber("ORD-1");
        item.setListingId("chain-1");
        item.setProductId("variant-1");
        item.setBrand("Nike");
        item.setTitle("Nike Dunk Low Panda");
        item.setStyleId("DD1391-100");
        item.setSize("10");
        item.setEuSize("44");
        item.setSalePrice(new BigDecimal("122"));
        item.setCurrencyCode("USD");
        item.setOrderStatus("已完成");
        item.setSoldOn(new Date(1788400000000L));

        MockHttpServletResponse response = export(task, item);

        List<StockXPurchaseOrderExcel> rows = EasyExcel.read(
                        new ByteArrayInputStream(response.getContentAsByteArray()))
                .head(StockXPurchaseOrderExcel.class).sheet().doReadSync();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getOrderNumber()).isEqualTo("ORD-1");
            assertThat(row.getChainId()).isEqualTo("chain-1");
            assertThat(row.getPurchasePrice()).isEqualTo("$122");
            assertThat(row.getOrderStatus()).isEqualTo("已完成");
            assertThat(row.getPurchaseTime()).isNotEqualTo("-");
        });
    }

    @Test
    void exportsPurchaseGuidanceWithPoisonPrice() throws Exception {
        TaskDO task = new TaskDO();
        task.setId(92L);
        task.setPlatform("stockx");
        task.setTaskType("purchase_guidance");
        TaskItemDO item = new TaskItemDO();
        item.setStyleId("DD1391-100");
        item.setSize("10");
        item.setEuSize("44");
        item.setLowestPrice(new BigDecimal("110"));
        item.setPoisonPrice(new BigDecimal("699"));

        MockHttpServletResponse response = export(task, item);

        List<StockXPurchaseGuidanceExcel> rows = EasyExcel.read(
                        new ByteArrayInputStream(response.getContentAsByteArray()))
                .head(StockXPurchaseGuidanceExcel.class).sheet().doReadSync();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getLowestAskPrice()).isEqualByComparingTo("110");
            assertThat(row.getPoisonPrice()).isEqualByComparingTo("699");
        });
    }

    private MockHttpServletResponse export(TaskDO task, TaskItemDO item) throws Exception {
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        when(taskMapper.selectById(task.getId())).thenReturn(task);
        when(itemMapper.selectByCondition(task.getId(), null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(List.of(item));
        TaskItemController controller = new TaskItemController();
        setField(controller, "taskItemMapper", itemMapper);
        setField(controller, "taskMapper", taskMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.exportTaskItems(task.getId(), null, null, null, null, response);
        return response;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
