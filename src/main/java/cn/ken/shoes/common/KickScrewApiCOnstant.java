package cn.ken.shoes.common;

public class KickScrewApiConstant {

    /**
     * 查询分类相关的信息
     */
    public static final String CATEGORY = "https://7ccjsevco9-dsn.algolia.net/1/indexes/*/queries";

    /**
     * 查询商品，可以通过品牌、类别进行查询（参数不同）
     */
    public static final String SEARCH_ITEMS = "https://www.kickscrew.com/_next/data/ncP0UtJDEbZJfVQ_9gyKX/en-US/search.json";

    /**
     * 用于查询商品尺码+价格，实际上该接口可以做很多功能（直接前端拼接sql作为参数进行查询）
     */
    public static final String SEARCH_ITEM_SIZE_PRICE = "https://kickscrewshop.myshopify.com/api/2024-04/graphql.json";

    /**
     * B端批量上传商品api
     */
    public static final String BATCH_UPLOAD_ITEMS = "https://api.crewsupply.kickscrew.com/sapi/v2/stock/batch-update";
}
