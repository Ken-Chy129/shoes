package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockXListingReconciliationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void includesLegacyAsksSubgraphFailuresAndRecoversActiveListing() throws Exception {
        List<StockXAccount> originalAccounts = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount account = new StockXAccount();
        account.setName("account-1");
        StockXConfig.setAccounts(List.of(account));
        try {
            StockXClient client = mock(StockXClient.class);
            TaskItemMapper itemMapper = mock(TaskItemMapper.class);
            TaskMapper taskMapper = mock(TaskMapper.class);
            StockXService service = new StockXService();
            setField(service, "stockXClient", client);
            setField(service, "taskItemMapper", itemMapper);
            setField(service, "taskMapper", taskMapper);

            TaskItemDO legacyFailure = new TaskItemDO();
            legacyFailure.setId(101L);
            legacyFailure.setTaskId(20L);
            legacyFailure.setProductId("variant-1");
            legacyFailure.setOperateResult("上架失败:Failed to fetch from Subgraph 'asks'.");
            legacyFailure.setOperateTime(new Date(System.currentTimeMillis() - 10 * 60 * 1000L));
            when(itemMapper.selectList(any())).thenReturn(List.of(legacyFailure));

            TaskDO task = new TaskDO();
            task.setId(20L);
            task.setAccountName("account-1");
            when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
            when(client.verifyListingsByVariantIds(List.of("variant-1"), account))
                    .thenReturn(Map.of("variant-1", new JSONObject().fluentPut("status", "ACTIVE")));

            service.reconcilePendingListings();

            ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(itemMapper).selectList(query.capture());
            assertThat(query.getValue().getCustomSqlSegment()).contains("LIKE");
            assertThat(query.getValue().getParamNameValuePairs().values())
                    .anyMatch(value -> String.valueOf(value)
                            .contains("Failed to fetch from Subgraph 'asks'"));
            ArgumentCaptor<TaskItemDO> update = ArgumentCaptor.forClass(TaskItemDO.class);
            verify(itemMapper).updateById(update.capture());
            assertThat(update.getValue().getOperateResult()).isEqualTo("已上架");
        } finally {
            StockXConfig.setAccounts(originalAccounts);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = StockXService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
