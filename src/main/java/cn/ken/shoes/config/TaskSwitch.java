package cn.ken.shoes.config;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.ListingFetchMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final ConcurrentHashMap<String, Map<String, ShoesContext.PriceDownConfig>> EXCEL_INPUT_MAP =
            new ConcurrentHashMap<>();

    public static String buildExcelKey(String accountId, String inventoryType) {
        return buildExcelKey(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static String buildExcelKey(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        ListingFetchMode effectiveMode = fetchMode != null ? fetchMode : ListingFetchMode.ALL;
        return accountId + ":" + inventoryType + ":" + effectiveMode.getCode();
    }

    public static boolean isExcelCancelled(String accountId, String inventoryType) {
        return isExcelCancelled(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static boolean isExcelCancelled(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return Boolean.TRUE.equals(EXCEL_CANCEL_MAP.get(buildExcelKey(accountId, inventoryType, fetchMode)));
    }

    public static void cancelExcel(String accountId, String inventoryType) {
        cancelExcel(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static void cancelExcel(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        EXCEL_CANCEL_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), true);
    }

    public static void resetExcelCancel(String accountId, String inventoryType) {
        resetExcelCancel(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static void resetExcelCancel(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        EXCEL_CANCEL_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), false);
    }

    public static Long getExcelTaskId(String accountId, String inventoryType) {
        return getExcelTaskId(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static Long getExcelTaskId(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return EXCEL_TASK_ID_MAP.get(buildExcelKey(accountId, inventoryType, fetchMode));
    }

    public static void setExcelTaskId(String accountId, String inventoryType, Long taskId) {
        setExcelTaskId(accountId, inventoryType, ListingFetchMode.ALL, taskId);
    }

    public static void setExcelTaskId(String accountId, String inventoryType, ListingFetchMode fetchMode, Long taskId) {
        EXCEL_TASK_ID_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), taskId);
    }

    public static void removeExcelTaskId(String accountId, String inventoryType) {
        removeExcelTaskId(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static void removeExcelTaskId(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        EXCEL_TASK_ID_MAP.remove(buildExcelKey(accountId, inventoryType, fetchMode));
    }

    public static int getExcelRound(String accountId, String inventoryType) {
        return getExcelRound(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static int getExcelRound(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return EXCEL_ROUND_MAP.getOrDefault(buildExcelKey(accountId, inventoryType, fetchMode), 0);
    }

    public static int incrementExcelRound(String accountId, String inventoryType) {
        return incrementExcelRound(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static int incrementExcelRound(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        String key = buildExcelKey(accountId, inventoryType, fetchMode);
        return EXCEL_ROUND_MAP.merge(key, 1, Integer::sum);
    }

    public static void resetExcelRound(String accountId, String inventoryType) {
        resetExcelRound(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static void resetExcelRound(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        EXCEL_ROUND_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), 0);
    }

    public static void setExcelRound(String accountId, String inventoryType, int round) {
        setExcelRound(accountId, inventoryType, ListingFetchMode.ALL, round);
    }

    public static void setExcelRound(String accountId, String inventoryType, ListingFetchMode fetchMode, int round) {
        EXCEL_ROUND_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), Math.max(round, 0));
    }

    public static long getExcelInterval(String accountId, String inventoryType) {
        return getExcelInterval(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static long getExcelInterval(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        // 逐任务间隔由 setExcelIntervalRuntime 在建任务时 seed；未 seed 时回退默认 30 分钟
        Long cached = EXCEL_INTERVAL_MAP.get(buildExcelKey(accountId, inventoryType, fetchMode));
        return cached != null ? cached : 30 * 60 * 1000L;
    }

    /**
     * 仅设置运行时轮询间隔（不回写账号配置），用于「逐任务」间隔：
     * 建任务时按本次填写的间隔 seed，任务结束由 clearExcelState 清除，不污染账号默认值。
     */
    public static void setExcelIntervalRuntime(String accountId, String inventoryType, long interval) {
        setExcelIntervalRuntime(accountId, inventoryType, ListingFetchMode.ALL, interval);
    }

    public static void setExcelIntervalRuntime(String accountId, String inventoryType,
                                               ListingFetchMode fetchMode, long interval) {
        EXCEL_INTERVAL_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), interval);
    }

    public static boolean isExcelRunning(String accountId, String inventoryType) {
        return isExcelRunning(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static boolean isExcelRunning(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return Boolean.TRUE.equals(EXCEL_RUNNING_MAP.get(buildExcelKey(accountId, inventoryType, fetchMode)));
    }

    public static boolean isAnyExcelRunning(String accountId, String inventoryType) {
        for (ListingFetchMode fetchMode : ListingFetchMode.values()) {
            if (isExcelRunning(accountId, inventoryType, fetchMode)) {
                return true;
            }
        }
        return false;
    }

    public static boolean tryStartExcel(String accountId, String inventoryType) {
        return tryStartExcel(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static boolean tryStartExcel(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return tryStart(EXCEL_RUNNING_MAP, buildExcelKey(accountId, inventoryType, fetchMode));
    }

    public static void setExcelRunning(String accountId, String inventoryType, boolean running) {
        setExcelRunning(accountId, inventoryType, ListingFetchMode.ALL, running);
    }

    public static void setExcelRunning(String accountId, String inventoryType,
                                       ListingFetchMode fetchMode, boolean running) {
        EXCEL_RUNNING_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), running);
    }

    public static Map<String, Boolean> getAllExcelRunningStatus() {
        return new HashMap<>(EXCEL_RUNNING_MAP);
    }

    public static List<Long> getAllExcelTaskIds() {
        return new ArrayList<>(EXCEL_TASK_ID_MAP.values());
    }

    public static void clearExcelState(String accountId, String inventoryType) {
        for (ListingFetchMode fetchMode : ListingFetchMode.values()) {
            clearExcelState(accountId, inventoryType, fetchMode);
        }
    }

    public static void clearExcelState(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        String key = buildExcelKey(accountId, inventoryType, fetchMode);
        EXCEL_CANCEL_MAP.remove(key);
        EXCEL_ROUND_MAP.remove(key);
        EXCEL_RUNNING_MAP.remove(key);
        EXCEL_PROCESS_OUTSIDE_MAP.remove(key);
        EXCEL_UNPROFITABLE_ACTION_MAP.remove(key);
        EXCEL_INTERVAL_MAP.remove(key);
        EXCEL_INPUT_MAP.remove(key);
    }

    public static boolean isProcessOutsideExcel(String accountId, String inventoryType) {
        return isProcessOutsideExcel(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static boolean isProcessOutsideExcel(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return Boolean.TRUE.equals(EXCEL_PROCESS_OUTSIDE_MAP.get(buildExcelKey(accountId, inventoryType, fetchMode)));
    }

    public static void setProcessOutsideExcel(String accountId, String inventoryType, boolean value) {
        setProcessOutsideExcel(accountId, inventoryType, ListingFetchMode.ALL, value);
    }

    public static void setProcessOutsideExcel(String accountId, String inventoryType,
                                              ListingFetchMode fetchMode, boolean value) {
        EXCEL_PROCESS_OUTSIDE_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), value);
    }

    public static String getUnprofitableAction(String accountId, String inventoryType) {
        return getUnprofitableAction(accountId, inventoryType, ListingFetchMode.ALL);
    }

    public static String getUnprofitableAction(String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return EXCEL_UNPROFITABLE_ACTION_MAP.getOrDefault(
                buildExcelKey(accountId, inventoryType, fetchMode), "markup");
    }

    public static void setUnprofitableAction(String accountId, String inventoryType, String action) {
        setUnprofitableAction(accountId, inventoryType, ListingFetchMode.ALL, action);
    }

    public static void setUnprofitableAction(String accountId, String inventoryType,
                                             ListingFetchMode fetchMode, String action) {
        EXCEL_UNPROFITABLE_ACTION_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), action);
    }

    public static void setPriceDownInput(String accountId, String inventoryType,
                                         ListingFetchMode fetchMode,
                                         Map<String, ShoesContext.PriceDownConfig> input) {
        Map<String, ShoesContext.PriceDownConfig> snapshot = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        EXCEL_INPUT_MAP.put(buildExcelKey(accountId, inventoryType, fetchMode), snapshot);
    }

    public static Map<String, ShoesContext.PriceDownConfig> getPriceDownInput(
            String accountId, String inventoryType, ListingFetchMode fetchMode) {
        return EXCEL_INPUT_MAP.getOrDefault(buildExcelKey(accountId, inventoryType, fetchMode), Map.of());
    }

    // ==================== StockX 搜索上架任务 ====================
    /** 搜索上架支持同账号多任务并行，运行态按 taskId 隔离，避免互相取消。 */
    private static final ConcurrentHashMap<Long, Boolean> SEARCH_LIST_CANCELLED_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> SEARCH_LIST_RUNNING_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> SEARCH_VERIFICATION_CANCELLED_MAP = new ConcurrentHashMap<>();

    public static List<Long> getAllSearchListTaskIds() {
        return new ArrayList<>(SEARCH_LIST_RUNNING_MAP.keySet());
    }

    public static boolean isSearchListCancelled(Long taskId) {
        return taskId != null && Boolean.TRUE.equals(SEARCH_LIST_CANCELLED_MAP.get(taskId));
    }

    public static void cancelSearchList(Long taskId) {
        if (taskId != null) {
            SEARCH_LIST_CANCELLED_MAP.put(taskId, true);
        }
    }

    public static void resetSearchListCancel(Long taskId) {
        if (taskId != null) {
            SEARCH_LIST_CANCELLED_MAP.remove(taskId);
        }
    }

    public static boolean isSearchListRunning(Long taskId) {
        return taskId != null && Boolean.TRUE.equals(SEARCH_LIST_RUNNING_MAP.get(taskId));
    }

    public static void markSearchListRunning(Long taskId) {
        if (taskId != null) {
            SEARCH_LIST_RUNNING_MAP.put(taskId, true);
        }
    }

    public static void clearSearchListRunState(Long taskId) {
        if (taskId != null) {
            SEARCH_LIST_CANCELLED_MAP.remove(taskId);
            SEARCH_LIST_RUNNING_MAP.remove(taskId);
        }
    }

    public static void cancelSearchVerification(Long taskId) {
        if (taskId != null) {
            SEARCH_VERIFICATION_CANCELLED_MAP.put(taskId, true);
        }
    }

    public static void resetSearchVerification(Long taskId) {
        if (taskId != null) {
            SEARCH_VERIFICATION_CANCELLED_MAP.remove(taskId);
        }
    }

    public static boolean isSearchVerificationCancelled(Long taskId) {
        return taskId != null && Boolean.TRUE.equals(SEARCH_VERIFICATION_CANCELLED_MAP.get(taskId));
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

    public static boolean tryStartFetchListings(String key) {
        return tryStart(FETCH_LISTINGS_RUNNING_MAP, key);
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

    public static boolean tryStartExcelDelist(String key) {
        return tryStart(EXCEL_DELIST_RUNNING_MAP, key);
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

    public static boolean tryStartFetchOrders(String accountId) {
        return tryStart(FETCH_ORDERS_RUNNING_MAP, accountId);
    }

    public static void setFetchOrdersRunning(String accountId, boolean running) {
        FETCH_ORDERS_RUNNING_MAP.put(accountId, running);
    }

    public static void clearFetchOrdersState(String accountId) {
        FETCH_ORDERS_CANCELLED_MAP.remove(accountId);
        FETCH_ORDERS_RUNNING_MAP.remove(accountId);
    }

    // ==================== StockX 购买任务 ====================
    private static final ConcurrentHashMap<String, Boolean> PURCHASE_CANCELLED_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> PURCHASE_RUNNING_MAP = new ConcurrentHashMap<>();

    public static boolean isPurchaseCancelled(String accountId) {
        return Boolean.TRUE.equals(PURCHASE_CANCELLED_MAP.get(accountId));
    }

    public static void cancelPurchase(String accountId) {
        PURCHASE_CANCELLED_MAP.put(accountId, true);
    }

    public static void resetPurchaseCancel(String accountId) {
        PURCHASE_CANCELLED_MAP.remove(accountId);
    }

    public static boolean tryStartPurchase(String accountId) {
        return tryStart(PURCHASE_RUNNING_MAP, accountId);
    }

    public static void setPurchaseRunning(String accountId, boolean running) {
        PURCHASE_RUNNING_MAP.put(accountId, running);
    }

    public static void clearPurchaseState(String accountId) {
        PURCHASE_CANCELLED_MAP.remove(accountId);
        PURCHASE_RUNNING_MAP.remove(accountId);
    }

    private static boolean tryStart(ConcurrentHashMap<String, Boolean> runningMap, String key) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        runningMap.compute(key, (ignored, running) -> {
            if (Boolean.TRUE.equals(running)) {
                return true;
            }
            acquired.set(true);
            return true;
        });
        return acquired.get();
    }
}
