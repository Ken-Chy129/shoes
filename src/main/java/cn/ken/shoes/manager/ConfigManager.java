package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.*;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.ConfigService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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

    // ==================== 压价 Excel 数据持久化（重启恢复用） ====================
    private static final String PRICE_DOWN_DIR = "files/pricedown";

    private static String priceDownFileName(String accountId, String inventoryType) {
        return accountId + "__" + inventoryType + ".json";
    }

    /**
     * 保存某账号+库存类型的压价 Excel 数据到磁盘。上传 Excel 后调用，使数据能在服务重启后恢复。
     */
    public void savePriceDownExcel(String accountId, String inventoryType) {
        Path path = Paths.get(PRICE_DOWN_DIR, priceDownFileName(accountId, inventoryType));
        try {
            Files.createDirectories(path.getParent());
            ConcurrentHashMap<String, ShoesContext.PriceDownConfig> map = ShoesContext.getPriceDownMap(accountId, inventoryType);
            JSONObject obj = new JSONObject();
            map.forEach((k, cfg) -> {
                JSONObject v = new JSONObject();
                v.put("minPrice", cfg.minPrice());
                v.put("skip", cfg.skip());
                obj.put(k, v);
            });
            Files.writeString(path, obj.toJSONString());
        } catch (Exception e) {
            System.err.println("Failed to save pricedown excel " + accountId + ":" + inventoryType + " - " + e.getMessage());
        }
    }

    /**
     * 删除某账号+库存类型的压价 Excel 持久化文件。无 Excel 启动压价任务时调用，避免旧数据在重启后复活。
     */
    public void deletePriceDownExcel(String accountId, String inventoryType) {
        try {
            Files.deleteIfExists(Paths.get(PRICE_DOWN_DIR, priceDownFileName(accountId, inventoryType)));
        } catch (Exception e) {
            System.err.println("Failed to delete pricedown excel " + accountId + ":" + inventoryType + " - " + e.getMessage());
        }
    }

    /**
     * 启动时把所有已持久化的压价 Excel 数据回填内存。必须在 TaskExecutorManager.resumeRunningTasks() 之前调用。
     */
    public void loadAllPriceDownExcel() {
        Path dir = Paths.get(PRICE_DOWN_DIR);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOnePriceDownFile);
        } catch (Exception e) {
            System.err.println("Failed to load pricedown excel dir: " + e.getMessage());
        }
    }

    private void loadOnePriceDownFile(Path path) {
        String base = path.getFileName().toString();
        base = base.substring(0, base.length() - ".json".length());
        String inventoryType;
        String accountId;
        // 按已知的库存类型后缀切分，兼容账号名本身含下划线
        if (base.endsWith("__CUSTODIAL")) {
            inventoryType = "CUSTODIAL";
            accountId = base.substring(0, base.length() - "__CUSTODIAL".length());
        } else if (base.endsWith("__STANDARD")) {
            inventoryType = "STANDARD";
            accountId = base.substring(0, base.length() - "__STANDARD".length());
        } else {
            return;
        }
        try {
            JSONObject obj = JSON.parseObject(Files.readString(path));
            ConcurrentHashMap<String, ShoesContext.PriceDownConfig> map = ShoesContext.getPriceDownMap(accountId, inventoryType);
            map.clear();
            for (String key : obj.keySet()) {
                JSONObject v = obj.getJSONObject(key);
                map.put(key, new ShoesContext.PriceDownConfig(v.getIntValue("minPrice"), v.getBooleanValue("skip")));
            }
            System.out.println("恢复压价Excel数据: " + accountId + ":" + inventoryType + " 共" + map.size() + "条");
        } catch (Exception e) {
            System.err.println("Failed to load pricedown excel file " + path + " - " + e.getMessage());
        }
    }
}
