package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class TaskCleanupService {

    private static final int RETENTION_DAYS = 7;
    private static final int BATCH_SIZE = 20;

    private final TaskMapper taskMapper;

    public TaskCleanupService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Scheduled(cron = "${task.cleanup.cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void scheduledCleanup() {
        cleanupExpiredTasks();
    }

    public synchronized CleanupResult cleanupExpiredTasks() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -RETENTION_DAYS);
        return cleanupExpiredTasks(calendar.getTime());
    }

    CleanupResult cleanupExpiredTasks(Date beforeDate) {
        long start = System.currentTimeMillis();
        long afterId = 0L;
        int deletedTasks = 0;
        long deletedRows = 0L;
        int failedTasks = 0;

        while (true) {
            List<Long> taskIds = taskMapper.selectExpiredTaskIds(beforeDate, afterId, BATCH_SIZE);
            if (taskIds == null || taskIds.isEmpty()) {
                break;
            }
            afterId = taskIds.getLast();
            for (Long taskId : taskIds) {
                try {
                    int affectedRows = taskMapper.deleteExpiredTaskWithItems(taskId, beforeDate);
                    if (affectedRows > 0) {
                        deletedTasks++;
                        deletedRows += affectedRows;
                    }
                } catch (Exception e) {
                    failedTasks++;
                    log.error("清理过期任务失败, taskId:{}", taskId, e);
                }
            }
        }

        long costMs = System.currentTimeMillis() - start;
        log.info("历史任务清理完成, before:{}, deletedTasks:{}, deletedRows:{}, failedTasks:{}, costMs:{}",
                beforeDate, deletedTasks, deletedRows, failedTasks, costMs);
        return new CleanupResult(deletedTasks, deletedRows, failedTasks);
    }

    public record CleanupResult(int deletedTasks, long deletedRows, int failedTasks) {
    }
}
