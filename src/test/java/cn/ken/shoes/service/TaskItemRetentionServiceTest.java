package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskItemRetentionServiceTest {

    @Test
    void trimsOnlyOldNoopRoundsOfPriceDownAndOldEbayItems() {
        Date now = new Date(1_800_000_000_000L);
        TaskDO priceDown = task(1L, "price_down", "running", 10, null);
        TaskDO earlyPriceDown = task(2L, "price_down", "running", 2, null);
        TaskDO ebay = task(3L, "ebay_price_sync", "running", 5, null);
        Date ebayEnd = new Date(now.getTime() - TimeUnit.DAYS.toMillis(2));
        TaskDO endedEbay = task(4L, "ebay_profit_price", "cancel", 5, ebayEnd);

        List<String> calls = new ArrayList<>();
        Deque<Integer> noopResults = new ArrayDeque<>(List.of(TaskItemRetentionService.BATCH_SIZE, 7));
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> {
            if (!"selectTrimCandidates".equals(method)) return null;
            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) args[0];
            return types.contains("price_down") ? List.of(priceDown, earlyPriceDown) : List.of(ebay, endedEbay);
        });
        TaskItemMapper itemMapper = proxy(TaskItemMapper.class, (method, args) -> switch (method) {
            case "deleteStaleNoopItems" -> {
                calls.add("noop:" + args[0] + ":" + args[1]);
                yield noopResults.poll();
            }
            case "deleteItemsOperatedBefore" -> {
                calls.add("time:" + args[0] + ":" + ((Date) args[1]).getTime());
                yield 3;
            }
            default -> null;
        });

        long deleted = new TaskItemRetentionService(taskMapper, itemMapper, 0).trimRunningTasks(now);

        long running = now.getTime() - TimeUnit.DAYS.toMillis(TaskItemRetentionService.EBAY_RETENTION_DAYS);
        long ended = ebayEnd.getTime() - TimeUnit.DAYS.toMillis(TaskItemRetentionService.EBAY_RETENTION_DAYS);
        int beforeRound = 10 - TaskItemRetentionService.KEEP_RECENT_ROUNDS + 1;
        assertThat(calls).containsExactly(
                "noop:1:" + beforeRound, "noop:1:" + beforeRound,
                "time:3:" + running, "time:4:" + ended);
        assertThat(deleted).isEqualTo(TaskItemRetentionService.BATCH_SIZE + 7 + 3 + 3);
    }

    @Test
    void stopsWhenBatchBudgetIsExhausted() {
        TaskDO priceDown = task(1L, "price_down", "running", 100, null);
        int[] batches = {0};
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) ->
                "selectTrimCandidates".equals(method) ? List.of(priceDown) : null);
        TaskItemMapper itemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if ("deleteStaleNoopItems".equals(method) || "deleteItemsOperatedBefore".equals(method)) {
                batches[0]++;
                return TaskItemRetentionService.BATCH_SIZE;
            }
            return null;
        });

        new TaskItemRetentionService(taskMapper, itemMapper, 0).trimRunningTasks(new Date());

        assertThat(batches[0]).isEqualTo(TaskItemRetentionService.MAX_BATCHES_PER_RUN);
    }

    private static TaskDO task(Long id, String type, String status, Integer round, Date endTime) {
        TaskDO task = new TaskDO();
        task.setId(id);
        task.setTaskType(type);
        task.setStatus(status);
        task.setRound(round);
        task.setEndTime(endTime);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.call(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args) throws Exception;
    }
}
