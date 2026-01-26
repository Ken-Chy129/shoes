package cn.ken.shoes.config;

/**
 * @author Ken-Chy129
 * @date 2025/5/19
 */
public class TaskSwitch {

    public static boolean STOP_KC_TASK = true;

    public static long KC_TASK_INTERVAL = 60 * 1000;

    public static boolean STOP_STOCK_TASK = true;

    // 上架任务开关
    public static boolean STOP_STOCK_LISTING_TASK = true;

    // 上架任务间隔
    public static long STOCK_LISTING_TASK_INTERVAL = 30 * 60 * 1000;

    // 压价任务开关
    public static boolean STOP_STOCK_PRICE_DOWN_TASK = true;

    // 压价任务间隔
    public static long STOCK_PRICE_DOWN_TASK_INTERVAL = 30 * 60 * 1000;

    @Deprecated
    public static long STOCK_TASK_INTERVAL = 30 * 60 * 1000;

    public static long STOP_INTERVAL = 10 * 60 * 1000;
}
