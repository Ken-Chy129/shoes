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
        when(service.start(anyLong(), any(), any(), anyList())).thenReturn(100L);
    }

    @Test
    void refusesToResumeWhenOverrideSnapshotMissing() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.empty());

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(2))).isNull();

        verify(service, never()).start(anyLong(), any(), any(), anyList());
        verify(taskMapper).updateTaskFailReason(eq(9L), contains("快照不存在"));
    }

    @Test
    void refusesToResumeWhenOverrideSnapshotUnreadableOrIncomplete() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenThrow(new IllegalStateException("bad json"));
        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(1))).isNull();

        reset(snapshotStore);
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.of(List.of(row())));
        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(2))).isNull();

        verify(service, never()).start(anyLong(), any(), any(), anyList());
    }

    @Test
    void resumesWithOverridesWhenSnapshotMatches() {
        List<EbayProfitTargetExcel> rows = List.of(row());
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.of(rows));

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(1))).isEqualTo(100L);
        verify(service).start(1L, new BigDecimal("50"), new BigDecimal("25"), rows);
    }

    @Test
    void resumesWithDefaultOnlyWhenTaskHadNoOverrides() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.empty());

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params(0))).isEqualTo(100L);
        verify(service).start(1L, new BigDecimal("50"), new BigDecimal("25"), List.of());
    }

    @Test
    void resumePreservesTaskFreightAndLegacyTaskFallsBackToGlobal() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.empty());
        Integer previous = cn.ken.shoes.config.PriceSwitch.FREIGHT;
        try {
            cn.ken.shoes.config.PriceSwitch.FREIGHT = 70;
            assertThat(manager.startEbayProfitPriceFromSnapshot(source,
                    params(0).fluentPut("freight", new BigDecimal("35.50")))).isEqualTo(100L);
            verify(service).start(1L, new BigDecimal("50"), new BigDecimal("35.50"), List.of());
            JSONObject legacy = params(0);
            legacy.remove("freight");
            assertThat(manager.startEbayProfitPriceFromSnapshot(source, legacy)).isEqualTo(100L);
            verify(service).start(1L, new BigDecimal("50"), new BigDecimal("70"), List.of());
        } finally {
            cn.ken.shoes.config.PriceSwitch.FREIGHT = previous;
        }
    }

    @Test
    void resumesPromoTaskWithDiscountParams() {
        when(snapshotStore.loadEbayProfitTargets(9L)).thenReturn(Optional.empty());
        when(service.startPromo(anyLong(), any(), any(), any(), any(), anyList())).thenReturn(101L);
        source.setTaskType(EbayProfitPriceService.PROMO_TASK_TYPE);
        JSONObject params = params(0).fluentPut("discountPercent", new BigDecimal("15"))
                .fluentPut("sellerSharePercent", new BigDecimal("70"));

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, params)).isEqualTo(101L);
        verify(service).startPromo(1L, new BigDecimal("50"), new BigDecimal("25"), new BigDecimal("15"),
                new BigDecimal("70"), List.of());
        verify(service, never()).start(anyLong(), any(), any(), anyList());
    }

    @Test
    void resumesNewProfitTaskFromRuleSnapshot() {
        source.setTaskType(EbayProfitPriceService.TASK_TYPE);
        when(service.start(anyLong(), any(EbayProfitPriceService.Rules.class))).thenReturn(102L);
        List<cn.ken.shoes.model.excel.EbayProfitRuleExcel> rows = List.of(
                new cn.ken.shoes.model.excel.EbayProfitRuleExcel("DD1391-100", "42", EbayProfitPriceService.HOLD));
        when(snapshotStore.loadEbayProfitRules(9L)).thenReturn(Optional.of(rows));

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, ruleParams(1))).isEqualTo(102L);
        verify(service).start(eq(1L), argThat((EbayProfitPriceService.Rules r) -> r.leadMinSkuCount() == 5
                && r.targets().containsKey("DD1391-100|42") && r.targets().get("DD1391-100|42") == null));
        verify(service, never()).start(anyLong(), any(), any(), anyList());
    }

    @Test
    void refusesToResumeNewProfitTaskWhenRuleSnapshotIncomplete() {
        source.setTaskType(EbayProfitPriceService.TASK_TYPE);
        when(snapshotStore.loadEbayProfitRules(9L)).thenReturn(Optional.empty());

        assertThat(manager.startEbayProfitPriceFromSnapshot(source, ruleParams(2))).isNull();
        verify(service, never()).start(anyLong(), any(EbayProfitPriceService.Rules.class));
        verify(taskMapper).updateTaskFailReason(eq(9L), contains("快照不存在"));
    }

    private static JSONObject ruleParams(int ruleCount) {
        List<BigDecimal> freights = java.util.Collections.nCopies(EbayProfitPriceService.SIZE_BUCKETS,
                new BigDecimal("30"));
        return new JSONObject().fluentPut("intervalHours", 1).fluentPut("agFreights", freights)
                .fluentPut("nonAgFreights", freights).fluentPut("profitThreshold", new BigDecimal("500"))
                .fluentPut("profitLow", new BigDecimal("30")).fluentPut("profitHigh", new BigDecimal("80"))
                .fluentPut("leadMinSkuCount", 5).fluentPut("leadProfit", new BigDecimal("-20"))
                .fluentPut("ruleCount", ruleCount);
    }

    private static JSONObject params(int overrideCount) {
        return new JSONObject().fluentPut("intervalHours", 1).fluentPut("defaultProfit", new BigDecimal("50"))
                .fluentPut("freight", new BigDecimal("25"))
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
