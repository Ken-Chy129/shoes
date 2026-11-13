package cn.ken.shoes.controller;

import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXOrderTaskExcel;
import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskItemControllerPendingClassificationTest {
    @Test
    void exportsThreeStatesAndDoesNotClassifyHistoricalOrders() throws Exception {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskItemMapper items = mock(TaskItemMapper.class);
        TaskDO task = new TaskDO();
        task.setId(98L);
        task.setTaskType("fetch_orders");
        when(tasks.selectById(98L)).thenReturn(task);
        List<TaskItemDO> records = List.of(item("待处理", true), item("待处理", false),
                item("待处理", null), item("销售完成", true));
        when(items.selectByCondition(98L, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(records);
        TaskItemController controller = new TaskItemController();
        ReflectionTestUtils.setField(controller, "taskMapper", tasks);
        ReflectionTestUtils.setField(controller, "taskItemMapper", items);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportTaskItems(98L, null, null, null, null, response);

        List<Map<Integer, String>> rows = EasyExcel.read(
                new ByteArrayInputStream(response.getContentAsByteArray())).headRowNumber(0).sheet().doReadSync();
        int column = rows.getFirst().entrySet().stream()
                .filter(entry -> "是否补单".equals(entry.getValue())).findFirst().orElseThrow().getKey();
        assertThat(rows.subList(1, rows.size())).extracting(row -> row.get(column))
                .containsExactly("补单", "正常单", "未判定", "-");
        List<StockXOrderTaskExcel> typedRows = EasyExcel.read(
                new ByteArrayInputStream(response.getContentAsByteArray()))
                .head(StockXOrderTaskExcel.class).sheet().doReadSync();
        assertThat(typedRows).extracting(StockXOrderTaskExcel::getExtensionStatus)
                .containsExactly("已延期", "已延期", "已延期", "-");
    }

    @Test
    void jsonPreservesFalseAndNullAsDistinctStates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Boolean value : Arrays.asList(true, false, null)) {
            var json = mapper.readTree(mapper.writeValueAsString(item("待处理", value)));
            assertThat(json.has("replenishmentOrder")).isTrue();
            if (value == null) {
                assertThat(json.get("replenishmentOrder").isNull()).isTrue();
            } else {
                assertThat(json.get("replenishmentOrder").booleanValue()).isEqualTo(value);
            }
        }
    }

    private TaskItemDO item(String status, Boolean replenishment) {
        TaskItemDO item = new TaskItemDO();
        item.setOrderStatus(status);
        item.setReplenishmentOrder(replenishment);
        item.setOperateResult("已延期");
        return item;
    }
}
