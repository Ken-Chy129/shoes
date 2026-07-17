package cn.ken.shoes.listener;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class ConfigLoadListener implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private ConfigManager configManager;

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private TaskItemMapper taskItemMapper;

    @Resource
    private TaskExecutorManager taskExecutorManager;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        System.out.println("Loading configurations from files...");
        try {
            configManager.loadPoisonConfig();
            configManager.loadPriceConfig();
            configManager.loadStockXConfig();
            System.out.println("Configurations loaded successfully.");
        } catch (Exception e) {
            System.err.println("Failed to load configurations: " + e.getMessage());
        }
        cleanExpiredTaskData();
        // 回填压价 Excel 数据到内存（必须在 resumeRunningTasks 之前，否则恢复的压价任务会因数据为空而空跑或击穿最低价）
        try {
            configManager.loadAllPriceDownExcel();
            configManager.loadAllDelistExcel();
        } catch (Exception e) {
            log.error("重启恢复StockX任务Excel数据失败", e);
        }
        // 账号配置加载完成后，自动恢复重启前运行中的任务（依赖 StockXConfig.getAccount，必须在 loadStockXConfig 之后）
        try {
            taskExecutorManager.resumeRunningTasks();
        } catch (Exception e) {
            log.error("重启恢复运行中任务失败", e);
        }
    }

    private void cleanExpiredTaskData() {
        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -7);
            Date oneWeekAgo = cal.getTime();
            List<Long> expiredTaskIds = taskMapper.selectIdsBeforeDate(oneWeekAgo);
            if (expiredTaskIds == null || expiredTaskIds.isEmpty()) {
                return;
            }
            for (Long taskId : expiredTaskIds) {
                taskItemMapper.deleteByTaskId(taskId);
                taskMapper.deleteById(taskId);
            }
            log.info("已清理一周前的历史任务数据，共{}条", expiredTaskIds.size());
        } catch (Exception e) {
            log.error("清理历史任务数据失败", e);
        }
    }
}
