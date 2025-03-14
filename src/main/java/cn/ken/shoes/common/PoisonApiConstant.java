package cn.ken.shoes.common;

public class PoisonApiConstant {

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

    /**
     * 根据spu查询价格
     */
    public static final String PRICE_BY_SPU = "http://47.100.28.62:8000/getpricebyspuidv3/z";

    /**
     * 根据token余额
     */
    public static final String TOKEN_BALANCE = "http://47.100.28.62:8000/getFundsbytoken";

    /**
     * 根据spu查询价格
     */
    public static final String PRICE_BY_SPU_V2 = "http://134.175.182.182:6699/api/dewu/redis/price_token?spuid={spuId}&token={token}";

    /**
     * 得物查价接口
     */
    public static final String PRICE_BY_SPU_V3 = "http://8.142.81.233:8999/api/v1/dewu/productPrice?spu_id={spuId}";
}
