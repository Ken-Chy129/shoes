package cn.ken.shoes.controller;

import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayListingTaskExcel;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskItemControllerEbayExportTest {

    @Test
    void exportsEbayIdentifiersAndListingInputs() throws Exception {
        TaskItemMapper itemMapper = mock(TaskItemMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskDO task = new TaskDO();
        task.setId(88L);
        task.setTaskType("ebay_bulk_listing");
        TaskItemDO item = new TaskItemDO();
        item.setSku("EBAY-DD1391-100-USM10-NEW");
        item.setOfferId("offer-1");
        item.setListingId("listing-1");
        item.setStyleId("DD1391-100");
        item.setSize("USM10");
        item.setListingQuantity(1);
        item.setCurrentPrice(new BigDecimal("129.99"));
        item.setCurrencyCode("USD");
        item.setOperateResult("上架成功");
        when(taskMapper.selectById(88L)).thenReturn(task);
        when(itemMapper.selectByCondition(88L, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(List.of(item));
        TaskItemController controller = new TaskItemController();
        setField(controller, "taskItemMapper", itemMapper);
        setField(controller, "taskMapper", taskMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportTaskItems(88L, null, null, null, null, response);

        List<EbayListingTaskExcel> rows = EasyExcel.read(
                        new java.io.ByteArrayInputStream(response.getContentAsByteArray()))
                .head(EbayListingTaskExcel.class).sheet().doReadSync();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getSku()).isEqualTo("EBAY-DD1391-100-USM10-NEW");
            assertThat(row.getOfferId()).isEqualTo("offer-1");
            assertThat(row.getListingId()).isEqualTo("listing-1");
            assertThat(row.getPrice()).isEqualByComparingTo("129.99");
        });
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = TaskItemController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
