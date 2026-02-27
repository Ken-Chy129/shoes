package cn.ken.shoes.config;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.util.SpringContextUtil;
import lombok.Data;

public class KickScrewConfig {

    public static final OAuthConfig CONFIG = new OAuthConfig();

    /**
     * 保存OAuth配置到文件
     */
    public static void saveOAuthConfig() {
        try {
            ConfigManager configManager = SpringContextUtil.getBean(ConfigManager.class);
            if (configManager != null) {
                configManager.saveKickScrewOAuthConfig();
            }
        } catch (Exception e) {
            System.err.println("Failed to save kickscrew oauth config: " + e.getMessage());
        }
    }

    @Data
    public static class OAuthConfig {
        private String accessToken;
    }
}
