package cn.ken.shoes.manager;

import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.EbayProfitTargetExcel;
import cn.ken.shoes.service.EbayProfitPriceService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskExecutorManagerEbayProfitPriceTest {

    private TaskMapper taskMapper;
    private TaskInputSnapshotStore snapshotStore;
    private EbayProfitPriceService service;
    private TaskExecutorManager manager;
    private TaskDO source;

    @BeforeEach
    void setUp() throws Exception {
        taskMapper = mock(TaskMapper.class);
        snapshotStore = mock(TaskInputSnapshotStore.class);
        service = mock(EbayProfitPriceService.class);
        manager = new TaskExecutorManager();
        set("taskMapper", taskMapper);
        set("taskInputSnapshotStore", snapshotStore);
        set("ebayProfitPriceService", service);
        source = new TaskDO();
        source.setId(9L);
        when(service.start(anyLong(), any(), anyList())).thenReturn(100L);
    }

    @Test
    void refusesToResumeWhenOverrideSnapshotMissing() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.empty());

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(2))).isNull();

        verify(service, never()).start(anyLong(), any(), anyList());
        verify(taskMapper).updateTaskFailReason(eq(9L), contains("快照不存在"));
    }

    @Test
    void refusesToResumeWhenOverrideSnapshotUnreadableOrIncomplete() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenThrow(new IllegalStateException("bad json"));
        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(1))).isNull();

        reset(snapshotStore);
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.of(List.of(row())));
        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(2))).isNull();

        verify(service, never()).start(anyLong(), any(), anyList());
    }

    @Test
    void resumesWithOverridesWhenSnapshotMatches() {
        List<EbayProfitTargetExcel> rows = List.of(row());
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.of(rows));

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(1))).isEqualTo(100L);
        verify(service).start(1L, new BigDecimal("50"), rows);
    }

    @Test
    void resumesWithDefaultOnlyWhenTaskHadNoOverrides() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.empty());

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(0))).isEqualTo(100L);
        verify(service).start(1L, new BigDecimal("50"), List.of());
    }

    private static JSONObject params(int overrideCount) {
        return new JSONObject().fluentPut("intervalHours", 1).fluentPut("defaultProfit", new BigDecimal("50"))
                .fluentPut("overrideCount", overrideCount);
    }

    private static EbayProfitTargetExcel row() {
        EbayProfitTargetExcel row = new EbayProfitTargetExcel();
        row.setStyleId("DD1391-100");
        row.setTargetProfit(new BigDecimal("80"));
        return row;
    }

    private void set(String name, Object value) throws Exception {
        Field field = TaskExecutorManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }
}
