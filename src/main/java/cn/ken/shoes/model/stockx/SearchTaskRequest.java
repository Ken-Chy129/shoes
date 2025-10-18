package cn.ken.shoes.model.stockx;

import lombok.Data;

@Data
public class SearchTaskRequest {

    /**
     * 搜索关键词
     */
    private String query;

    /**
     * 排序规则，逗号分隔，如 "featured,lowest_ask"
     */
    private String sorts;

    /**
     * 每个sort查询的页数
     */
    private Integer pageCount;

    /**
     * 商品类别: shoes-鞋类, apparel-服饰
     */
    private String category;
}
