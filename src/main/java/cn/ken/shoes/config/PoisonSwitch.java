package cn.ken.shoes.config;

import cn.ken.shoes.manager.ConfigManager;
import cn.ken.shoes.util.SpringContextUtil;

public class PoisonSwitch {

    /**
     * 查询价格接口
     */
    public static Integer API_MODE = 1;

    /**
     * 价格上限
     */
    public static Integer MAX_PRICE = 5000;

    /**
     * 是否暂停查价
     */
    public static Boolean STOP_QUERY_PRICE = false;

    /**
     * 开启启动时自动同步DB数据
     */
    public static Boolean OPEN_IMPORT_DB_DATA = true;

    /**
     * 开启无价货号缓存
     */
    public static Boolean OPEN_NO_PRICE_CACHE = true;

    /**
     * 最小利润
     */
    public static Integer MIN_PROFIT = -30;

    /**
     * 3.5最小利润
     */
    public static Integer MIN_THREE_PROFIT = -30;

    /**
     * 开启全量货号3.5
     */
    public static Boolean OPEN_ALL_THREE_FIVE = false;

    /**
     * 得物主查价是否切换到官方 POP B2B 接口。
     *
     * <p>默认必须保持 false：当前生产继续使用 partner batchprice。后续确认 POP 价格口径后，
     * 将 files/config/poison-config.properties 中 use.pop.api 改为 true 并重启，即可优先启用
     * querySpuList -> querySpuBidPrice 两段式链路。POP 异常时仍会自动回退现有接口。</p>
     */
    public static Boolean USE_POP_API = false;

    public static Boolean USE_V2_API = false;

    public static Boolean USE_V3_API = true;

    public static Boolean USE_V4_API = true;

    /**
     * 保存配置到文件
     */
    public static void saveConfig() {
        try {
            ConfigManager configManager = SpringContextUtil.getBean(ConfigManager.class);
            if (configManager != null) {
                configManager.savePoisonConfig();
            }
        } catch (Exception e) {
            System.err.println("Failed to save poison config: " + e.getMessage());
        }
    }
}
