package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 运行中周期任务的明细滚动清理。
 * <p>压价/eBay 改价任务会一直 running、每轮把整批商品重写一遍明细，而 {@link TaskCleanupService}
 * 只按开始时间清理 7 天前已结束的任务，导致单个任务累积上百万行明细，拖慢任务列表统计和对账查询。这里在任务运行期间：
 * <ul>
 *   <li>压价任务：删除 {@value #KEEP_RECENT_ROUNDS} 轮之前的无操作明细（保持-/跳过-/无须），真实压价/下架/待对账结果全部保留，
 *       任务列表的成功统计不受影响；</li>
 *   <li>eBay 定时改价/盈利改价：明细不参与统计，只保留最近 {@value #EBAY_RETENTION_DAYS} 天。</li>
 * </ul>
 * 按小批次删除并设置单次运行上限，避免长事务和持续占满磁盘 IO。
 */
@Slf4j
@Service
public class TaskItemRetentionService {

    static final int KEEP_RECENT_ROUNDS = 3;
    static final int EBAY_RETENTION_DAYS = 3;
    static final int CANDIDATE_ENDED_WITHIN_DAYS = 8;
    static final int BATCH_SIZE = 2000;
    static final int MAX_BATCHES_PER_RUN = 100;
    private static final long PAUSE_BETWEEN_BATCHES_MS = 50;

    static final List<String> ROUND_BASED_TYPES = List.of("price_down");
    static final List<String> TIME_BASED_TYPES = List.of("ebay_price_sync", "ebay_profit_price");

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final long pauseMs;

    @Autowired
    public TaskItemRetentionService(TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this(taskMapper, taskItemMapper, PAUSE_BETWEEN_BATCHES_MS);
    }

    TaskItemRetentionService(TaskMapper taskMapper, TaskItemMapper taskItemMapper, long pauseMs) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.pauseMs = pauseMs;
    }

    @Scheduled(initialDelay = 10 * 60 * 1000, fixedDelay = 10 * 60 * 1000)
    public void scheduledTrim() {
        try {
            trimRunningTasks(new Date());
        } catch (Exception e) {
            log.error("运行中任务明细滚动清理失败", e);
        }
    }

    public synchronized long trimRunningTasks(Date now) {
        long start = System.currentTimeMillis();
        Budget budget = new Budget(MAX_BATCHES_PER_RUN);
        long deleted = 0;

        // 已结束任务在 TaskCleanupService 删除前（按开始时间保留 7 天）同样会被滚动清理
        Date endedAfter = new Date(now.getTime() - TimeUnit.DAYS.toMillis(CANDIDATE_ENDED_WITHIN_DAYS));
        List<TaskDO> roundTasks = taskMapper.selectTrimCandidates(ROUND_BASED_TYPES, endedAfter);
        for (TaskDO task : roundTasks == null ? List.<TaskDO>of() : roundTasks) {
            Integer round = task.getRound();
            if (task.getId() == null || round == null || round <= KEEP_RECENT_ROUNDS) {
                continue;
            }
            int beforeRound = round - KEEP_RECENT_ROUNDS + 1;
            deleted += drain(budget, () -> taskItemMapper.deleteStaleNoopItems(task.getId(), beforeRound, BATCH_SIZE));
        }

        List<TaskDO> timeTasks = taskMapper.selectTrimCandidates(TIME_BASED_TYPES, endedAfter);
        for (TaskDO task : timeTasks == null ? List.<TaskDO>of() : timeTasks) {
            if (task.getId() == null) {
                continue;
            }
            // 已结束任务以结束时间为基准，保留结束前最后 N 天的明细
            long anchor = task.getEndTime() != null && !"running".equals(task.getStatus())
                    ? Math.min(task.getEndTime().getTime(), now.getTime()) : now.getTime();
            Date cutoff = new Date(anchor - TimeUnit.DAYS.toMillis(EBAY_RETENTION_DAYS));
            deleted += drain(budget, () -> taskItemMapper.deleteItemsOperatedBefore(task.getId(), cutoff, BATCH_SIZE));
        }

        if (deleted > 0 || budget.exhausted()) {
            log.info("运行中任务明细滚动清理完成, deletedRows:{}, batches:{}, budgetExhausted:{}, costMs:{}",
                    deleted, budget.used, budget.exhausted(), System.currentTimeMillis() - start);
        }
        return deleted;
    }

    private long drain(Budget budget, BatchDelete batchDelete) {
        long deleted = 0;
        while (!budget.exhausted()) {
            budget.used++;
            int affected = batchDelete.run();
            deleted += affected;
            if (affected < BATCH_SIZE) {
                break;
            }
            pause();
        }
        return deleted;
    }

    private void pause() {
        if (pauseMs <= 0) {
            return;
        }
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface BatchDelete {
        int run();
    }

    private static final class Budget {
        private final int max;
        private int used;

        private Budget(int max) {
            this.max = max;
        }

        private boolean exhausted() {
            return used >= max;
        }
    }
}
