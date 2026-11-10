package cn.ken.shoes.model.kickscrew;

import java.util.List;

/**
 * KC Storefront 关键词搜索的一页结果（游标分页）。
 */
public record KickScrewKeywordSearchPage(List<Item> items, boolean hasNextPage, String endCursor) {

    public record Item(String modelNo, String title, String brand, String productType,
                       String handle, Boolean availableForSale) {
    }
}
