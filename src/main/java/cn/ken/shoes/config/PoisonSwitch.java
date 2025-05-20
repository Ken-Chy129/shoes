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
}
