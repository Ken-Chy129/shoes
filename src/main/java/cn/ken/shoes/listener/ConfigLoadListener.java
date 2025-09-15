package cn.ken.shoes.listener;

import cn.ken.shoes.manager.ConfigManager;
import jakarta.annotation.Resource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class ConfigLoadListener implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private ConfigManager configManager;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        System.out.println("Loading configurations from files...");
        try {
            configManager.loadPoisonConfig();
            configManager.loadPriceConfig();
            configManager.loadStockXConfig();
            configManager.loadStockXOAuthConfig();
            System.out.println("Configurations loaded successfully.");
        } catch (Exception e) {
            System.err.println("Failed to load configurations: " + e.getMessage());
        }
    }
}