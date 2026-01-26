package cn.ken.shoes.manager;

import cn.ken.shoes.config.*;
import cn.ken.shoes.common.StockXPriceEnum;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.service.ConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class ConfigManager {

    @Resource
    private ConfigService configService;

    private static final String POISON_CONFIG_FILE = "poison-config.properties";
    private static final String PRICE_CONFIG_FILE = "price-config.properties";
    private static final String STOCKX_CONFIG_FILE = "stockx-config.properties";
    private static final String STOCKX_OAUTH_CONFIG_FILE = "stockx-oauth-config.properties";

    @PostConstruct
    public void initConfigs() {
        loadPoisonConfig();
        loadPriceConfig();
        loadStockXConfig();
        loadStockXOAuthConfig();
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
        PoisonSwitch.USE_V2_API = configService.getBooleanProperty(properties, "use.v2.api", true);
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
        properties.setProperty("use.v2.api", String.valueOf(PoisonSwitch.USE_V2_API));
        
        configService.saveConfig(POISON_CONFIG_FILE, properties);
    }

    public void loadPriceConfig() {
        Properties properties = configService.loadConfig(PRICE_CONFIG_FILE);
        
        PriceSwitch.EXCHANGE_RATE = configService.getDoubleProperty(properties, "exchange.rate", 7.3d);
        PriceSwitch.FREIGHT = configService.getIntProperty(properties, "freight", 25);
        PriceSwitch.KC_GET_RATE = configService.getDoubleProperty(properties, "kc.get.rate", 0.89d);
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
        String priceType = configService.getProperty(properties, "price.type", "SELL_FASTER");

        try {
            StockXSwitch.SORT_TYPE = StockXSortEnum.valueOf(sortType);
        } catch (IllegalArgumentException e) {
            StockXSwitch.SORT_TYPE = StockXSortEnum.FEATURED;
        }

        try {
            StockXSwitch.PRICE_TYPE = StockXPriceEnum.valueOf(priceType);
        } catch (IllegalArgumentException e) {
            StockXSwitch.PRICE_TYPE = StockXPriceEnum.SELL_FASTER;
        }

        // 任务配置
        StockXSwitch.TASK_PRICE_DOWN_THREAD_COUNT = configService.getIntProperty(properties, "task.price.down.thread.count", 1);
        StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE = configService.getIntProperty(properties, "task.price.down.per.minute", 60);
        StockXSwitch.TASK_LISTING_SORT = configService.getProperty(properties, "task.listing.sort", "CREATED_AT");
        StockXSwitch.TASK_LISTING_ORDER = configService.getProperty(properties, "task.listing.order", "DESC");
    }

    public void saveStockXConfig() {
        Properties properties = new Properties();

        properties.setProperty("sort.type", StockXSwitch.SORT_TYPE.name());
        properties.setProperty("price.type", StockXSwitch.PRICE_TYPE.name());

        // 任务配置
        properties.setProperty("task.price.down.thread.count", String.valueOf(StockXSwitch.TASK_PRICE_DOWN_THREAD_COUNT));
        properties.setProperty("task.price.down.per.minute", String.valueOf(StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE));
        properties.setProperty("task.listing.sort", StockXSwitch.TASK_LISTING_SORT);
        properties.setProperty("task.listing.order", StockXSwitch.TASK_LISTING_ORDER);

        configService.saveConfig(STOCKX_CONFIG_FILE, properties);
    }

    public void loadStockXOAuthConfig() {
        Properties properties = configService.loadConfig(STOCKX_OAUTH_CONFIG_FILE);
        
        StockXConfig.CONFIG.setAccessToken(configService.getProperty(properties, "access.token", null));
        StockXConfig.CONFIG.setRefreshToken(configService.getProperty(properties, "refresh.token", null));
        StockXConfig.CONFIG.setIdToken(configService.getProperty(properties, "id.token", null));
        StockXConfig.CONFIG.setExpireTime(configService.getProperty(properties, "expire.time", null));
    }

    public void saveStockXOAuthConfig() {
        Properties properties = new Properties();
        
        if (StockXConfig.CONFIG.getAccessToken() != null) {
            properties.setProperty("access.token", StockXConfig.CONFIG.getAccessToken());
        }
        if (StockXConfig.CONFIG.getRefreshToken() != null) {
            properties.setProperty("refresh.token", StockXConfig.CONFIG.getRefreshToken());
        }
        if (StockXConfig.CONFIG.getIdToken() != null) {
            properties.setProperty("id.token", StockXConfig.CONFIG.getIdToken());
        }
        if (StockXConfig.CONFIG.getExpireTime() != null) {
            properties.setProperty("expire.time", StockXConfig.CONFIG.getExpireTime());
        }
        
        configService.saveConfig(STOCKX_OAUTH_CONFIG_FILE, properties);
    }
}