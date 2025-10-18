package cn.ken.shoes.common;

import lombok.Getter;

import java.util.List;

/**
 * @author Ken-Chy129
 * @date 2025/10/18
 */
@Getter
public enum SearchTypeEnum {

    SHOES("shoes", "鞋类", List.of(StockXCategoryEnum.SHOES.getCode(), StockXCategoryEnum.SNEAKERS.getCode())),
    CLOTHES("clothes", "服饰", List.of(StockXCategoryEnum.APPAREL.getCode())),
    ;

    private final String code;
    private final String name;
    private final List<String> categories;

    SearchTypeEnum(String code, String name, List<String> categories) {
        this.code = code;
        this.name = name;
        this.categories = categories;
    }

    public static SearchTypeEnum from(String code) {
        for (SearchTypeEnum e : SearchTypeEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }
}
