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

    // ==================== StockX Excel 多账号压价任务（动态Map） ====================
    private static final ConcurrentHashMap<String, Boolean> EXCEL_CANCEL_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EXCEL_TASK_ID_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> EXCEL_ROUND_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EXCEL_INTERVAL_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> EXCEL_RUNNING_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> EXCEL_PROCESS_OUTSIDE_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> EXCEL_UNPROFITABLE_ACTION_MAP = new ConcurrentHashMap<>();

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
        // 逐任务间隔由 setExcelIntervalRuntime 在建任务时 seed；未 seed 时回退默认 30 分钟
        Long cached = EXCEL_INTERVAL_MAP.get(buildExcelKey(accountId, inventoryType));
        return cached != null ? cached : 30 * 60 * 1000L;
    }

    /**
     * 仅设置运行时轮询间隔（不回写账号配置），用于「逐任务」间隔：
     * 建任务时按本次填写的间隔 seed，任务结束由 clearExcelState 清除，不污染账号默认值。
     */
    public static void setExcelIntervalRuntime(String accountId, String inventoryType, long interval) {
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
        EXCEL_ROUND_MAP.remove(key);
        EXCEL_RUNNING_MAP.remove(key);
        EXCEL_PROCESS_OUTSIDE_MAP.remove(key);
        EXCEL_UNPROFITABLE_ACTION_MAP.remove(key);
        EXCEL_INTERVAL_MAP.remove(key);
    }

    public static boolean isProcessOutsideExcel(String accountId, String inventoryType) {
        return Boolean.TRUE.equals(EXCEL_PROCESS_OUTSIDE_MAP.get(buildExcelKey(accountId, inventoryType)));
    }

    public static void setProcessOutsideExcel(String accountId, String inventoryType, boolean value) {
        EXCEL_PROCESS_OUTSIDE_MAP.put(buildExcelKey(accountId, inventoryType), value);
    }

    public static String getUnprofitableAction(String accountId, String inventoryType) {
        return EXCEL_UNPROFITABLE_ACTION_MAP.getOrDefault(buildExcelKey(accountId, inventoryType), "markup");
    }

    public static void setUnprofitableAction(String accountId, String inventoryType, String action) {
        EXCEL_UNPROFITABLE_ACTION_MAP.put(buildExcelKey(accountId, inventoryType), action);
    }

    // ==================== StockX 搜索上架任务 ====================
    private static final ConcurrentHashMap<String, Long> SEARCH_LIST_TASK_ID_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> SEARCH_LIST_CANCELLED_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> SEARCH_LIST_RUNNING_MAP = new ConcurrentHashMap<>();

    public static void setSearchListTaskId(String accountId, Long taskId) {
        SEARCH_LIST_TASK_ID_MAP.put(accountId, taskId);
    }

    public static Long getSearchListTaskId(String accountId) {
        return SEARCH_LIST_TASK_ID_MAP.get(accountId);
    }

    public static List<Long> getAllSearchListTaskIds() {
        return new ArrayList<>(SEARCH_LIST_TASK_ID_MAP.values());
    }

    public static boolean isSearchListCancelled(String accountId) {
        return Boolean.TRUE.equals(SEARCH_LIST_CANCELLED_MAP.get(accountId));
    }

    public static void cancelSearchList(String accountId) {
        SEARCH_LIST_CANCELLED_MAP.put(accountId, true);
    }

    public static void resetSearchListCancel(String accountId) {
        SEARCH_LIST_CANCELLED_MAP.remove(accountId);
    }

    public static boolean isSearchListRunning(String accountId) {
        return Boolean.TRUE.equals(SEARCH_LIST_RUNNING_MAP.get(accountId));
    }

    public static void setSearchListRunning(String accountId, boolean running) {
        SEARCH_LIST_RUNNING_MAP.put(accountId, running);
    }

    public static void clearSearchListRunState(String accountId) {
        SEARCH_LIST_CANCELLED_MAP.remove(accountId);
        SEARCH_LIST_RUNNING_MAP.remove(accountId);
    }

    // ==================== StockX 获取上架商品任务 ====================
    private static final ConcurrentHashMap<String, Long> FETCH_LISTINGS_TASK_ID_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> FETCH_LISTINGS_CANCELLED_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> FETCH_LISTINGS_RUNNING_MAP = new ConcurrentHashMap<>();

    public static void setFetchListingsTaskId(String key, Long taskId) {
        FETCH_LISTINGS_TASK_ID_MAP.put(key, taskId);
    }

    public static Long getFetchListingsTaskId(String key) {
        return FETCH_LISTINGS_TASK_ID_MAP.get(key);
    }

    public static boolean isFetchListingsCancelled(String key) {
        return Boolean.TRUE.equals(FETCH_LISTINGS_CANCELLED_MAP.get(key));
    }

    public static void cancelFetchListings(String key) {
        FETCH_LISTINGS_CANCELLED_MAP.put(key, true);
    }

    public static void resetFetchListingsCancel(String key) {
        FETCH_LISTINGS_CANCELLED_MAP.remove(key);
    }

    public static boolean isFetchListingsRunning(String key) {
        return Boolean.TRUE.equals(FETCH_LISTINGS_RUNNING_MAP.get(key));
    }

    public static void setFetchListingsRunning(String key, boolean running) {
        FETCH_LISTINGS_RUNNING_MAP.put(key, running);
    }

    public static void clearFetchListingsState(String key) {
        FETCH_LISTINGS_CANCELLED_MAP.remove(key);
        FETCH_LISTINGS_RUNNING_MAP.remove(key);
    }

    // ==================== StockX Excel下架任务 ====================
    private static final ConcurrentHashMap<String, Long> EXCEL_DELIST_TASK_ID_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> EXCEL_DELIST_CANCELLED_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> EXCEL_DELIST_RUNNING_MAP = new ConcurrentHashMap<>();

    public static void setExcelDelistTaskId(String key, Long taskId) {
        EXCEL_DELIST_TASK_ID_MAP.put(key, taskId);
    }

    public static Long getExcelDelistTaskId(String key) {
        return EXCEL_DELIST_TASK_ID_MAP.get(key);
    }

    public static boolean isExcelDelistCancelled(String key) {
        return Boolean.TRUE.equals(EXCEL_DELIST_CANCELLED_MAP.get(key));
    }

    public static void cancelExcelDelist(String key) {
        EXCEL_DELIST_CANCELLED_MAP.put(key, true);
    }

    public static void resetExcelDelistCancel(String key) {
        EXCEL_DELIST_CANCELLED_MAP.remove(key);
    }

    public static boolean isExcelDelistRunning(String key) {
        return Boolean.TRUE.equals(EXCEL_DELIST_RUNNING_MAP.get(key));
    }

    public static void setExcelDelistRunning(String key, boolean running) {
        EXCEL_DELIST_RUNNING_MAP.put(key, running);
    }

    public static void clearExcelDelistState(String key) {
        EXCEL_DELIST_CANCELLED_MAP.remove(key);
        EXCEL_DELIST_RUNNING_MAP.remove(key);
    }

    // ==================== StockX 获取订单任务 ====================
    private static final ConcurrentHashMap<String, Boolean> FETCH_ORDERS_CANCELLED_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> FETCH_ORDERS_RUNNING_MAP = new ConcurrentHashMap<>();

    public static boolean isFetchOrdersCancelled(String accountId) {
        return Boolean.TRUE.equals(FETCH_ORDERS_CANCELLED_MAP.get(accountId));
    }

    public static void cancelFetchOrders(String accountId) {
        FETCH_ORDERS_CANCELLED_MAP.put(accountId, true);
    }

    public static void resetFetchOrdersCancel(String accountId) {
        FETCH_ORDERS_CANCELLED_MAP.remove(accountId);
    }

    public static boolean isFetchOrdersRunning(String accountId) {
        return Boolean.TRUE.equals(FETCH_ORDERS_RUNNING_MAP.get(accountId));
    }

    public static void setFetchOrdersRunning(String accountId, boolean running) {
        FETCH_ORDERS_RUNNING_MAP.put(accountId, running);
    }

    public static void clearFetchOrdersState(String accountId) {
        FETCH_ORDERS_CANCELLED_MAP.remove(accountId);
        FETCH_ORDERS_RUNNING_MAP.remove(accountId);
    }
}
