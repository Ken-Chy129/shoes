package cn.ken.shoes.config;

/**
 * @author Ken-Chy129
 * @date 2025/5/19
 */
public class TaskSwitch {

    // ==================== KC任务 ====================
    public static boolean CANCEL_KC_TASK = false;
    public static long KC_TASK_INTERVAL = 60 * 1000;
    public static Long CURRENT_KC_TASK_ID = null;
    public static int CURRENT_KC_ROUND = 0;

    // ==================== StockX上架任务 ====================
    public static boolean CANCEL_STOCK_LISTING_TASK = false;
    public static long STOCK_LISTING_TASK_INTERVAL = 30 * 60 * 1000;
    public static Long CURRENT_STOCK_LISTING_TASK_ID = null;
    public static int CURRENT_STOCK_LISTING_ROUND = 0;

    // ==================== StockX压价任务 ====================
    public static boolean CANCEL_STOCK_PRICE_DOWN_TASK = false;
    public static long STOCK_PRICE_DOWN_TASK_INTERVAL = 30 * 60 * 1000;
    public static Long CURRENT_STOCK_PRICE_DOWN_TASK_ID = null;
    public static int CURRENT_STOCK_PRICE_DOWN_ROUND = 0;
}
