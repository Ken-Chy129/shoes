package cn.ken.shoes.common;

public class KickScrewApiConstant {

    /**
     * 全文索引搜索
     */
    public static final String ALGOLIA = "https://7ccjsevco9-dsn.algolia.net/1/indexes/*/queries";

    /**
     * 查询商品，可以通过品牌、类别进行查询（参数不同）
     */
    public static final String SEARCH_ITEMS = "https://www.kickscrew.com/_next/data/ncP0UtJDEbZJfVQ_9gyKX/en-US/search.json";

    /**
     * 用于查询商品尺码+价格，实际上该接口可以做很多功能（直接前端拼接sql作为参数进行查询）
     */
    public static final String SEARCH_ITEM_SIZE_PRICE = "https://kickscrewshop.myshopify.com/api/2024-04/graphql.json";

    /**
     * 用于查询商品的尺码表
     */
    public static final String SEARCH_ITEM_SIZE = "https://api.size.storefront.kickscrew.com/v2/size-chart/{brand}/{modelNo}/json";

    /**
     * B端批量上传商品api
     */
    public static final String BATCH_UPLOAD_ITEMS = "https://api.crewsupply.kickscrew.com/sapi/v2/stock/batch-update";

    /**
     * B端清除上架商品
     */
    public static final String DELETE_ALL_ITEMS = "https://api.crewsupply.kickscrew.com/sapi/v2/stock/all";

    /**
     * B端清除指定商品
     */
    public static final String DELETE_LIST = "https://api.crewsupply.kickscrew.com/sapi/v2/stock";

    /**
     * B端查询订单
     */
    public static final String ORDER_LIST = "https://api.crewsupply.kickscrew.com/sapi/v2/orders";

    /**
     * B端取消订单
     */
    public static final String CANCEL_ORDER = "https://api.crewsupply.kickscrew.com/sapi/v2/order/out-stock";

    /**
     * B端查询商品
     */
    public static final String QUERY_ITEM_BY_MODEL_NO = "https://api.crewsupply.kickscrew.com/sapi/v2/product/{modelNo}";

    /**
     * B端查询平台最低价
     */
    public static final String QUERY_LOWEST_PRICE = "https://api.crewsupply.kickscrew.com/sapi/v2/bidding/platform-lowest";

    /**
     * B端查询上架商品
     */
    public static final String QUERY_STOCK = "https://api.crewsupply.kickscrew.com/sapi/v2/stock/list";

    /**
     * 下载订单qr标签
     */
    public static final String DOWNLOAD_QR_LABEL = "https://api.crewsupply.kickscrew.com/sapi/v2/order/label/{orderId}";
}
