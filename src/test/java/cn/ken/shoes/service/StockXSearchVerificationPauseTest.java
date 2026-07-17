package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StockXSearchVerificationPauseTest {

    @Test
    void pausedTaskDoesNotRunQueuedListingVerificationRequests() throws Exception {
        Long taskId = 77L;
        AtomicInteger apiCalls = new AtomicInteger();
        StockXClient client = new StockXClient() {
            @Override
            public Map<String, com.alibaba.fastjson.JSONObject> verifyListingsByVariantIds(
                    List<String> variantIds, StockXAccount account) {
                apiCalls.incrementAndGet();
                return Map.of();
            }
        };
        TaskItemMapper taskItemMapper = (TaskItemMapper) Proxy.newProxyInstance(
                TaskItemMapper.class.getClassLoader(), new Class<?>[]{TaskItemMapper.class},
                (proxy, method, args) -> method.getReturnType() == int.class ? 0 : null);
        StockXService service = new StockXService();
        setField(service, "stockXClient", client);
        setField(service, "taskItemMapper", taskItemMapper);
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        TaskSwitch.cancelSearchVerification(taskId);

        try {
            service.verifyCreateBatchAsync("batch-1", taskId, account,
                    List.of("variant-1"), Map.of("variant-1", 1L));

            assertThat(apiCalls).hasValue(0);
        } finally {
            TaskSwitch.resetSearchVerification(taskId);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = StockXService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
