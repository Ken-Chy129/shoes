package cn.ken.shoes.task;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.manager.StockXPriceUpdateCoordinator;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.StockXCustodialPriceInputExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockXCustodialPriceTaskRunnerTest {
    private final StockXClient client = mock(StockXClient.class);
    private final StockXPriceUpdateCoordinator coordinator = mock(StockXPriceUpdateCoordinator.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskItemMapper items = mock(TaskItemMapper.class);
    private final StockXAccount account = new StockXAccount();
    private final List<TaskItemDO> saved = new ArrayList<>();

    StockXCustodialPriceTaskRunnerTest() {
        account.setName("custodial-runner-fixture");
        TaskSwitch.tryStartCustodialPriceUpdate(account.getName());
        doAnswer(call -> {
            TaskItemDO item = call.getArgument(0);
            item.setId((long) saved.size() + 1);
            saved.add(item);
            return 1;
        }).when(items).insert(any(TaskItemDO.class));
        when(coordinator.submit(anyList(), eq(account), any(), any(), any(), any()))
                .thenAnswer(call -> new StockXPriceUpdateCoordinator.Submission("batch", call.getArgument(0)));
    }

    @AfterEach void cleanup() { TaskSwitch.clearCustodialPriceUpdate(account.getName()); }

    @Test void changesOnlyRequestedCustodialListingsAndConfirmsPrices() {
        when(client.queryCustodialPricePage(1, account)).thenReturn(page(true, node("change", 100), node("same", 120)));
        when(client.queryCustodialPricePage(2, account)).thenReturn(page(false, node("outside", 100)));
        when(client.verifyListingsByListingIds(List.of("change"), account, true))
                .thenReturn(Map.of("change", node("change", 120)));
        run(row("change", 120), row("same", 120), row("not-custodial", 120));
        verify(coordinator).submit(eq(List.of(Map.of("listingId", "change", "amount", "120", "currencyCode", "USD"))),
                eq(account), any(), any(), any(), any());
        assertThat(saved).extracting(TaskItemDO::getOperateResult)
                .containsExactly("改价成功", "保持-价格相同", "失败-未找到所选账号的寄存挂单");
        assertThat(saved.getFirst().getCurrentPrice()).isEqualByComparingTo("100");
        assertThat(saved.getFirst().getTargetPrice()).isEqualByComparingTo("120");
        verify(tasks).updateTaskFailed(eq(81L), contains("失败1"));
    }

    @Test void doesNotWriteIfLaterPageFailsOrRepeats() {
        when(client.queryCustodialPricePage(1, account)).thenReturn(page(true, node("change", 100)));
        when(client.queryCustodialPricePage(2, account)).thenReturn(page(false, node("change", 100)));
        run(row("change", 120));
        verifyNoInteractions(coordinator);
        verify(tasks).updateTaskFailed(eq(81L), anyString());
    }

    @Test void acceptedButUnchangedPriceIsNotSuccess() {
        when(client.queryCustodialPricePage(1, account)).thenReturn(page(false, node("change", 100)));
        when(client.verifyListingsByListingIds(List.of("change"), account, true))
                .thenReturn(Map.of("change", node("change", 100)));
        run(row("change", 120));
        assertThat(saved.getFirst().getOperateResult()).startsWith("未确认");
        verify(tasks, never()).updateTaskStatus(81L, "success");
    }

    @Test void cancelsWithoutWritingAndReleasesReservation() {
        TaskSwitch.cancelCustodialPriceUpdate(account.getName());
        run(row("change", 120));
        verifyNoInteractions(client, coordinator);
        verify(tasks).updateTaskStatus(81L, "cancel");
        assertThat(TaskSwitch.tryStartCustodialPriceUpdate(account.getName())).isTrue();
    }

    private void run(StockXCustodialPriceInputExcel... rows) {
        new StockXCustodialPriceTaskRunner(account, 81L, List.of(rows), client, coordinator, tasks, items) {
            @Override protected void waitForSync() { }
        }.run();
    }

    private StockXCustodialPriceInputExcel row(String id, int price) {
        StockXCustodialPriceInputExcel row = new StockXCustodialPriceInputExcel();
        row.setListingId(id);
        row.setPrice(BigDecimal.valueOf(price));
        return row;
    }

    private JSONObject node(String id, int amount) {
        return new JSONObject().fluentPut("id", id).fluentPut("amount", amount).fluentPut("currency", "USD");
    }

    private JSONObject page(boolean more, JSONObject... nodes) {
        return new JSONObject().fluentPut("pageInfo", new JSONObject().fluentPut("hasNextPage", more))
                .fluentPut("edges", JSON.toJSON(java.util.Arrays.stream(nodes)
                        .map(node -> Map.of("node", node)).toList()));
    }
}
