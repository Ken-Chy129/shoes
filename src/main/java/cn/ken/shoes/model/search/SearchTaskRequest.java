package cn.ken.shoes.model.search;

import lombok.Data;

@Data
public class SearchTaskRequest {

    /**
     * 搜索平台
     */
    private String platform;

    /**
     * 搜索关键词或货号
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
     * 任务类型: keyword-关键词搜索, modelNo-货号搜索
     */
    private String type;

    /**
     * 搜索类型类别: shoes-鞋类, clothes-服饰 (仅关键词搜索时使用)
     */
    private String searchType;
}
