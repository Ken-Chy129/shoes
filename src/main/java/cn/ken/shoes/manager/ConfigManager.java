package cn.ken.shoes.manager;

import cn.ken.shoes.config.*;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.ConfigService;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Component
public class ConfigManager {

    @Resource
    private ConfigService configService;

    private static final String POISON_CONFIG_FILE = "poison-config.properties";
    private static final String PRICE_CONFIG_FILE = "price-config.properties";
    private static final String STOCKX_CONFIG_FILE = "stockx-config.properties";
    private static final String KICKSCREW_OAUTH_CONFIG_FILE = "kickscrew-oauth-config.properties";
    private static final String STOCKX_ACCOUNTS_FILE = "stockx-accounts.json";

    @PostConstruct
    public void initConfigs() {
        loadPoisonConfig();
        loadPriceConfig();
        loadStockXConfig();
        loadStockXAccounts();
        loadKickScrewOAuthConfig();
    }

    public void loadPoisonConfig() {
        Properties properties = configService.loadConfig(POISON_CONFIG_FILE);
        
        PoisonSwitch.API_MODE = configService.getIntProperty(properties, "api.mode", 1);
        PoisonSwitch.MAX_PRICE = configService.getIntProperty(properties, "max.price", 5000);
        PoisonSwitch.STOP_QUERY_PRICE = configService.getBooleanProperty(properties, "stop.query.price", false);
        PoisonSwitch.OPEN_IMPORT_DB_DATA = configService.getBooleanProperty(properties, "open.import.db.data", true);
        PoisonSwitch.OPEN_NO_PRICE_CACHE = configService.getBooleanProperty(properties, "open.no.price.cache", true);
        PoisonSwitch.MIN_PROFIT = configService.getIntProperty(properties, "min.profit", -30);
        PoisonSwitch.MIN_THREE_PROFIT = configService.getIntProperty(properties, "min.three.profit", -30);
        PoisonSwitch.OPEN_ALL_THREE_FIVE = configService.getBooleanProperty(properties, "open.all.three.five", false);
        PoisonSwitch.USE_POP_API = configService.getBooleanProperty(properties, "use.pop.api", false);
        PoisonSwitch.USE_V2_API = configService.getBooleanProperty(properties, "use.v2.api", true);
        PoisonSwitch.USE_V3_API = configService.getBooleanProperty(properties, "use.v3.api", true);
        PoisonSwitch.USE_V4_API = configService.getBooleanProperty(properties, "use.v4.api", true);
    }

    public void savePoisonConfig() {
        Properties properties = new Properties();
        
        properties.setProperty("api.mode", String.valueOf(PoisonSwitch.API_MODE));
        properties.setProperty("max.price", String.valueOf(PoisonSwitch.MAX_PRICE));
        properties.setProperty("stop.query.price", String.valueOf(PoisonSwitch.STOP_QUERY_PRICE));
        properties.setProperty("open.import.db.data", String.valueOf(PoisonSwitch.OPEN_IMPORT_DB_DATA));
        properties.setProperty("open.no.price.cache", String.valueOf(PoisonSwitch.OPEN_NO_PRICE_CACHE));
        properties.setProperty("min.profit", String.valueOf(PoisonSwitch.MIN_PROFIT));
        properties.setProperty("min.three.profit", String.valueOf(PoisonSwitch.MIN_THREE_PROFIT));
        properties.setProperty("open.all.three.five", String.valueOf(PoisonSwitch.OPEN_ALL_THREE_FIVE));
        properties.setProperty("use.pop.api", String.valueOf(PoisonSwitch.USE_POP_API));
        properties.setProperty("use.v2.api", String.valueOf(PoisonSwitch.USE_V2_API));
        properties.setProperty("use.v3.api", String.valueOf(PoisonSwitch.USE_V3_API));
        properties.setProperty("use.v4.api", String.valueOf(PoisonSwitch.USE_V4_API));

        configService.saveConfig(POISON_CONFIG_FILE, properties);
    }

    public void loadPriceConfig() {
        Properties properties = configService.loadConfig(PRICE_CONFIG_FILE);
        
        PriceSwitch.EXCHANGE_RATE = configService.getDoubleProperty(properties, "exchange.rate", 6.8d);
        PriceSwitch.FREIGHT = configService.getIntProperty(properties, "freight", 25);
        PriceSwitch.KC_GET_RATE = configService.getDoubleProperty(properties, "kc.get.rate", 0.88d);
        PriceSwitch.KC_SERVICE_FEE = configService.getIntProperty(properties, "kc.service.fee", 18);
    }

    public void savePriceConfig() {
        Properties properties = new Properties();
        
        properties.setProperty("exchange.rate", String.valueOf(PriceSwitch.EXCHANGE_RATE));
        properties.setProperty("freight", String.valueOf(PriceSwitch.FREIGHT));
        properties.setProperty("kc.get.rate", String.valueOf(PriceSwitch.KC_GET_RATE));
        properties.setProperty("kc.service.fee", String.valueOf(PriceSwitch.KC_SERVICE_FEE));
        
        configService.saveConfig(PRICE_CONFIG_FILE, properties);
    }

    public void loadStockXConfig() {
        Properties properties = configService.loadConfig(STOCKX_CONFIG_FILE);

        String sortType = configService.getProperty(properties, "sort.type", "FEATURED");

        try {
            StockXSwitch.SORT_TYPE = StockXSortEnum.valueOf(sortType);
        } catch (IllegalArgumentException e) {
            StockXSwitch.SORT_TYPE = StockXSortEnum.FEATURED;
        }
    }

    public void saveStockXConfig() {
        Properties properties = new Properties();

        properties.setProperty("sort.type", StockXSwitch.SORT_TYPE.name());

        configService.saveConfig(STOCKX_CONFIG_FILE, properties);
    }

    public void loadKickScrewOAuthConfig() {
        Properties properties = configService.loadConfig(KICKSCREW_OAUTH_CONFIG_FILE);
        KickScrewConfig.CONFIG.setAccessToken(configService.getProperty(properties, "access.token", null));
    }

    public void saveKickScrewOAuthConfig() {
        Properties properties = new Properties();
        if (KickScrewConfig.CONFIG.getAccessToken() != null) {
            properties.setProperty("access.token", KickScrewConfig.CONFIG.getAccessToken());
        }
        configService.saveConfig(KICKSCREW_OAUTH_CONFIG_FILE, properties);
    }

    public void loadStockXAccounts() {
        Path path = Paths.get("files/config", STOCKX_ACCOUNTS_FILE);
        if (!Files.exists(path)) {
            StockXConfig.setAccounts(new ArrayList<>());
            return;
        }
        try {
            String json = Files.readString(path);
            List<StockXAccount> accounts = JSON.parseArray(json, StockXAccount.class);
            StockXConfig.setAccounts(accounts != null ? accounts : new ArrayList<>());
        } catch (Exception e) {
            System.err.println("Failed to load stockx accounts: " + e.getMessage());
            StockXConfig.setAccounts(new ArrayList<>());
        }
    }

    public void saveStockXAccounts() {
        Path path = Paths.get("files/config", STOCKX_ACCOUNTS_FILE);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.toJSONString(StockXConfig.getAccounts()));
        } catch (IOException e) {
            System.err.println("Failed to save stockx accounts: " + e.getMessage());
        }
    }
}
