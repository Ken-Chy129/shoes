package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务类型枚举
 */
@Getter
@AllArgsConstructor
public enum TaskTypeEnum {

    LISTING("listing", "上架"),
    PRICE_DOWN("price_down", "压价"),
    FETCH_LISTINGS("fetch_listings", "获取上架商品"),
    EXCEL_DELIST("excel_delist", "Excel下架"),
    ;

    private final String code;
    private final String desc;

    public static TaskTypeEnum fromCode(String code) {
        for (TaskTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
