package cn.ken.shoes.config;

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

    public static Boolean USE_V2_API = true;
}
