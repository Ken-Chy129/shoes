package cn.ken.shoes.config;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.util.SpringContextUtil;

public class PriceSwitch {

    /**
     * 汇率（美元->人民币）
     */
    public static Double EXCHANGE_RATE = 7.3d;

    /**
     * 运费
     */
    public static Integer FREIGHT = 25;

    /**
     * KC到手比例
     */
    public static Double KC_GET_RATE = 0.89d;

    /**
     * KC服务费
     */
    public static Integer KC_SERVICE_FEE = 18;

    /**
     * 保存配置到文件
     */
    public static void saveConfig() {
        try {
            ConfigManager configManager = SpringContextUtil.getBean(ConfigManager.class);
            if (configManager != null) {
                configManager.savePriceConfig();
            }
        } catch (Exception e) {
            System.err.println("Failed to save price config: " + e.getMessage());
        }
    }
}
