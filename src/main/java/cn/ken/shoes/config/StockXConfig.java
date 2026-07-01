package cn.ken.shoes.config;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.SpringContextUtil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StockXConfig {


    // ==================== 限流(429)处理全局默认值（无账号的调用兜底） ====================

    private static final List<StockXAccount> ACCOUNTS = new CopyOnWriteArrayList<>();

    public static List<StockXAccount> getAccounts() {
        return ACCOUNTS;
    }

    public static List<StockXAccount> getEnabledAccounts() {
        return ACCOUNTS.stream().filter(StockXAccount::isEnabled).toList();
    }

    public static StockXAccount getAccount(String name) {
        return ACCOUNTS.stream().filter(a -> a.getName().equals(name)).findFirst().orElse(null);
    }

    public static void setAccounts(List<StockXAccount> accounts) {
        ACCOUNTS.clear();
        ACCOUNTS.addAll(accounts);
    }

    public static void addAccount(StockXAccount account) {
        ACCOUNTS.add(account);
        saveAccounts();
    }

    public static void removeAccount(String name) {
        ACCOUNTS.removeIf(a -> a.getName().equals(name));
        saveAccounts();
    }

    public static void updateAccount(StockXAccount updated) {
        StockXAccount existing = getAccount(updated.getName());
        if (existing == null) {
            ACCOUNTS.add(updated);
        } else {
            // 原地更新字段而非替换引用：正在运行的长跑任务(如压价)持有的同一 account 引用，
            // 才能立即用上发token机刷新后的新 token，避免任务跑到旧 token 过期而报"Token已过期"。
            cn.hutool.core.bean.BeanUtil.copyProperties(updated, existing);
        }
        saveAccounts();
    }

    public static void saveAccounts() {
        try {
            ConfigManager configManager = SpringContextUtil.getBean(ConfigManager.class);
            if (configManager != null) {
                configManager.saveStockXAccounts();
            }
        } catch (Exception e) {
            System.err.println("Failed to save stockx accounts: " + e.getMessage());
        }
    }

    public static final String AUTHORIZE = "https://accounts.stockx.com/authorize?" +
            "response_type=code&" +
            "client_id={clientId}&" +
            "redirect_uri={redirectUri}&" +
            "scope=offline_access%20openid&" +
            "audience=gateway.stockx.com&" +
            "state={state}";

    public static final String TOKEN = "https://accounts.stockx.com/oauth/token";

    public static final String CALLBACK = "https://config.ken-chy129.cn/callback/getStockxCode";

    public static final String SEARCH_ITEMS = "https://api.stockx.com/v2/catalog/search";

    public static final String SEARCH_SIZE = "https://api.stockx.com/v2/catalog/products/{productId}/variants";

    public static final String SEARCH_PRICE = "https://api.stockx.com/v2/catalog/products/{productId}/market-data";

    public static final String CREATE_LISTING = "https://api.stockx.com/v2/selling/batch/create-listing";

    public static final String GET_LISTING_STATUS = "https://api.stockx.com/v2/selling/batch/create-listing/{batchId}";

    public static final String GRAPHQL = "https://gateway.stockx.com/api/graphql";

    public static final String BATCH_UPDATE_LISTING = "https://api.stockx.com/v2/selling/batch/update-listing";

    public static final String BATCH_UPDATE_LISTING_STATUS = "https://api.stockx.com/v2/selling/batch/update-listing/{batchId}";

}
