package cn.ken.shoes.config;

import cn.ken.shoes.common.StockXPriceEnum;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.util.SpringContextUtil;

public class StockXSwitch {

    public static StockXSortEnum SORT_TYPE = StockXSortEnum.FEATURED;

    public static StockXPriceEnum PRICE_TYPE = StockXPriceEnum.SELL_FASTER;

    // ==================== 任务配置 ====================

    // 压价线程数
    public static int TASK_PRICE_DOWN_THREAD_COUNT = 1;

    // 每分钟压价数
    public static int TASK_PRICE_DOWN_PER_MINUTE = 60;

    // 压价排序字段：CREATED_AT, ITEM_TITLE, BID_ASK_SPREAD, PRICE, LOWEST_ASK, HIGHEST_BID, SIZE, UPDATED_AT
    public static String TASK_LISTING_SORT = "CREATED_AT";

    // 压价排序顺序：ASC, DESC
    public static String TASK_LISTING_ORDER = "DESC";

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
