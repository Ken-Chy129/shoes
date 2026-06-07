package cn.ken.shoes.config;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.SpringContextUtil;
import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StockXConfig {

    public static final OAuth2Config CONFIG = new OAuth2Config();

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
        ACCOUNTS.replaceAll(a -> a.getName().equals(updated.getName()) ? updated : a);
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

    /**
     * 保存OAuth配置到文件
     */
    public static void saveOAuthConfig() {
        try {
            ConfigManager configManager = SpringContextUtil.getBean(ConfigManager.class);
            if (configManager != null) {
                configManager.saveStockXOAuthConfig();
            }
        } catch (Exception e) {
            System.err.println("Failed to save stockx oauth config: " + e.getMessage());
        }
    }

    @Data
    public static class OAuth2Config {
        private String accessToken;

        private String refreshToken;

        private String idToken;

        private String expireTime;
    }
}
