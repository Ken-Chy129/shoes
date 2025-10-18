package cn.ken.shoes.common;

import lombok.Getter;

/**
 * @author Ken-Chy129
 * @date 2025/10/18
 */
@Getter
public enum StockXCategoryEnum {

    SHOES("shoes"),
    SNEAKERS("sneakers"),
    APPAREL("apparel"),
    ;

    private final String code;

    StockXCategoryEnum(String code) {
        this.code = code;
    }
}
