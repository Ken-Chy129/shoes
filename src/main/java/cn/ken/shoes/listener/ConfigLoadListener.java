package cn.ken.shoes.listener;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.service.TaskCleanupService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ConfigLoadListener implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private ConfigManager configManager;

    @Resource
    private TaskCleanupService taskCleanupService;

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
        try {
            taskCleanupService.cleanupExpiredTasks();
        } catch (Exception e) {
            log.error("清理历史任务数据失败", e);
        }
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

}
