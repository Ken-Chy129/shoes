package cn.ken.shoes.manager;

import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.excel.StockXBidDeleteInputExcel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorManagerDeleteBidsTest {

    @TempDir
    Path tempDir;

    @Test
    void rerunsTargetedDeletionFromTheOriginalStyleIdSnapshot() throws Exception {
        TaskInputSnapshotStore snapshots = new TaskInputSnapshotStore(tempDir);
        snapshots.saveDeleteBidsInput(77L, List.of(deleteBid("STYLE-1")));
        AtomicReference<String> account = new AtomicReference<>();
        AtomicReference<List<StockXBidDeleteInputExcel>> input = new AtomicReference<>();
        TaskExecutorManager manager = new TaskExecutorManager() {
            @Override
            public Long startDeleteBids(String accountId, List<StockXBidDeleteInputExcel> inputRows) {
                account.set(accountId);
                input.set(inputRows);
                return 401L;
            }
        };
        setSnapshotStore(manager, snapshots);

        Long taskId = manager.rerunTask(source(77L,
                "{\"operation\":\"delete_bids\",\"deleteMode\":\"style_ids\",\"inputCount\":1}"));

        assertThat(taskId).isEqualTo(401L);
        assertThat(account.get()).isEqualTo("account-a");
        assertThat(input.get()).singleElement().satisfies(row ->
                assertThat(row.getStyleId()).isEqualTo("STYLE-1"));
    }

    @Test
    void keepsLegacyDeleteBidsTasksAsDeleteAllOnRerun() {
        AtomicReference<StockXPurchaseOperation> operation = new AtomicReference<>();
        TaskExecutorManager manager = new TaskExecutorManager() {
            @Override
            public Long startPurchase(String accountId, StockXPurchaseOperation selectedOperation) {
                operation.set(selectedOperation);
                return 402L;
            }
        };

        Long taskId = manager.rerunTask(source(78L, "{\"operation\":\"delete_bids\"}"));

        assertThat(taskId).isEqualTo(402L);
        assertThat(operation.get()).isEqualTo(StockXPurchaseOperation.DELETE_BIDS);
    }

    private static StockXBidDeleteInputExcel deleteBid(String styleId) {
        StockXBidDeleteInputExcel row = new StockXBidDeleteInputExcel();
        row.setStyleId(styleId);
        return row;
    }

    private static TaskDO source(Long taskId, String params) {
        TaskDO source = new TaskDO();
        source.setId(taskId);
        source.setPlatform("stockx");
        source.setTaskType("purchase");
        source.setAccountName("account-a");
        source.setParams(params);
        return source;
    }

    private static void setSnapshotStore(TaskExecutorManager manager,
                                         TaskInputSnapshotStore snapshots) throws Exception {
        Field field = TaskExecutorManager.class.getDeclaredField("taskInputSnapshotStore");
        field.setAccessible(true);
        field.set(manager, snapshots);
    }
}
