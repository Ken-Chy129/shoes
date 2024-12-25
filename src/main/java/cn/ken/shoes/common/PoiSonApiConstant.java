package cn.ken.shoes.common;

public class PoiSonApiConstant {

    /**
     * 通过货号批量获取商品spu信息
     */
    public static final String BATCH_ARTICLE_NUMBER = "/spu/batch_article_number";

    /**
     * 查询平台sku最低价【闪电直发】
     */
    public static final String LOWEST_PRICE = "/consign/bidding/lowest_price";

    /**
     * 获取平台sku最低价【极速现货】
     */
    public static final String FAST_LOWEST_PRICE = "/bidding/fast/fast_lowest_price";

    /**
     * 获取平台sku最低价【普通现货】
     */
    public static final String NORMAL_LOWEST_PRICE = "/bidding/normal/normal_lowest_price";


}
