package cn.ken.shoes.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Ken-Chy129
 * @date 2025/5/19
 */
public class TaskSwitch {

    // ==================== KC上架任务 ====================
    public static boolean CANCEL_KC_LISTING_TASK = false;
    public static long KC_LISTING_TASK_INTERVAL = 60 * 1000;
    public static Long CURRENT_KC_LISTING_TASK_ID = null;
    public static int CURRENT_KC_LISTING_ROUND = 0;

    // ==================== KC压价任务 ====================
    public static boolean CANCEL_KC_PRICE_DOWN_TASK = false;
    public static long KC_PRICE_DOWN_TASK_INTERVAL = 10 * 60 * 1000;
    public static Long CURRENT_KC_PRICE_DOWN_TASK_ID = null;
    public static int CURRENT_KC_PRICE_DOWN_ROUND = 0;

    // ==================== StockX上架任务 ====================
    public static boolean CANCEL_STOCK_LISTING_TASK = false;
    public static long STOCK_LISTING_TASK_INTERVAL = 30 * 60 * 1000;
    public static Long CURRENT_STOCK_LISTING_TASK_ID = null;
    public static int CURRENT_STOCK_LISTING_ROUND = 0;

    // ==================== StockX压价任务（非Excel，保留原有逻辑） ====================
    public static boolean CANCEL_STOCK_PRICE_DOWN_TASK = false;
    public static long STOCK_PRICE_DOWN_TASK_INTERVAL = 30 * 60 * 1000;
    public static Long CURRENT_STOCK_PRICE_DOWN_TASK_ID = null;
    public static int CURRENT_STOCK_PRICE_DOWN_ROUND = 0;

    // ==================== StockX现货压价任务（保留，供旧Runner使用） ====================
    public static boolean CANCEL_STOCK_STANDARD_PRICE_DOWN_TASK = false;
    public static long STOCK_STANDARD_PRICE_DOWN_TASK_INTERVAL = 30 * 60 * 1000;
    public static Long CURRENT_STOCK_STANDARD_PRICE_DOWN_TASK_ID = null;
    public static int CURRENT_STOCK_STANDARD_PRICE_DOWN_ROUND = 0;

    // ==================== StockX寄存压价任务（保留，供旧Runner使用） ====================
    public static boolean CANCEL_STOCK_CUSTODIAL_PRICE_DOWN_TASK = false;
    public static long STOCK_CUSTODIAL_PRICE_DOWN_TASK_INTERVAL = 30 * 60 * 1000;
    public static Long CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_TASK_ID = null;
    public static int CURRENT_STOCK_CUSTODIAL_PRICE_DOWN_ROUND = 0;

    // ==================== StockX Excel 多账号压价任务（动态Map） ====================
    private static final ConcurrentHashMap<String, Boolean> EXCEL_CANCEL_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EXCEL_TASK_ID_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> EXCEL_ROUND_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EXCEL_INTERVAL_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> EXCEL_RUNNING_MAP = new ConcurrentHashMap<>();

    public static String buildExcelKey(String accountId, String inventoryType) {
        return accountId + ":" + inventoryType;
    }

    public static boolean isExcelCancelled(String accountId, String inventoryType) {
        return Boolean.TRUE.equals(EXCEL_CANCEL_MAP.get(buildExcelKey(accountId, inventoryType)));
    }

    public static void cancelExcel(String accountId, String inventoryType) {
        EXCEL_CANCEL_MAP.put(buildExcelKey(accountId, inventoryType), true);
    }

    public static void resetExcelCancel(String accountId, String inventoryType) {
        EXCEL_CANCEL_MAP.put(buildExcelKey(accountId, inventoryType), false);
    }

    public static Long getExcelTaskId(String accountId, String inventoryType) {
        return EXCEL_TASK_ID_MAP.get(buildExcelKey(accountId, inventoryType));
    }

    public static void setExcelTaskId(String accountId, String inventoryType, Long taskId) {
        EXCEL_TASK_ID_MAP.put(buildExcelKey(accountId, inventoryType), taskId);
    }

    public static void removeExcelTaskId(String accountId, String inventoryType) {
        EXCEL_TASK_ID_MAP.remove(buildExcelKey(accountId, inventoryType));
    }

    public static int getExcelRound(String accountId, String inventoryType) {
        return EXCEL_ROUND_MAP.getOrDefault(buildExcelKey(accountId, inventoryType), 0);
    }

    public static int incrementExcelRound(String accountId, String inventoryType) {
        String key = buildExcelKey(accountId, inventoryType);
        return EXCEL_ROUND_MAP.merge(key, 1, Integer::sum);
    }

    public static void resetExcelRound(String accountId, String inventoryType) {
        EXCEL_ROUND_MAP.put(buildExcelKey(accountId, inventoryType), 0);
    }

    public static long getExcelInterval(String accountId, String inventoryType) {
        return EXCEL_INTERVAL_MAP.getOrDefault(buildExcelKey(accountId, inventoryType), 30 * 60 * 1000L);
    }

    public static void setExcelInterval(String accountId, String inventoryType, long interval) {
        EXCEL_INTERVAL_MAP.put(buildExcelKey(accountId, inventoryType), interval);
    }

    public static boolean isExcelRunning(String accountId, String inventoryType) {
        return Boolean.TRUE.equals(EXCEL_RUNNING_MAP.get(buildExcelKey(accountId, inventoryType)));
    }

    public static void setExcelRunning(String accountId, String inventoryType, boolean running) {
        EXCEL_RUNNING_MAP.put(buildExcelKey(accountId, inventoryType), running);
    }

    public static Map<String, Boolean> getAllExcelRunningStatus() {
        return new HashMap<>(EXCEL_RUNNING_MAP);
    }

    public static List<Long> getAllExcelTaskIds() {
        return new ArrayList<>(EXCEL_TASK_ID_MAP.values());
    }

    public static void clearExcelState(String accountId, String inventoryType) {
        String key = buildExcelKey(accountId, inventoryType);
        EXCEL_CANCEL_MAP.remove(key);
        EXCEL_TASK_ID_MAP.remove(key);
        EXCEL_ROUND_MAP.remove(key);
        EXCEL_RUNNING_MAP.remove(key);
    }
}
