package cn.ken.shoes.config;

import cn.ken.shoes.common.StockXPriceEnum;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.util.SpringContextUtil;

public class StockXSwitch {

    public static StockXSortEnum SORT_TYPE = StockXSortEnum.FEATURED;

    public static StockXPriceEnum PRICE_TYPE = StockXPriceEnum.SELL_FASTER;

    /**
     * 保存配置到文件
     */
    public static void saveConfig() {
        try {
            ConfigManager configManager = SpringContextUtil.getBean(ConfigManager.class);
            if (configManager != null) {
                configManager.saveStockXConfig();
            }
        } catch (Exception e) {
            System.err.println("Failed to save stockx config: " + e.getMessage());
        }
    }
}
