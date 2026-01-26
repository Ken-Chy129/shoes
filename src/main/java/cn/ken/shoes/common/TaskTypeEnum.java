package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务类型枚举
 */
@Getter
@AllArgsConstructor
public enum TaskTypeEnum {

    KC("kc", "KC改价任务"),
    STOCKX_LISTING("stockx_listing", "StockX上架任务"),
    STOCKX_PRICE_DOWN("stockx_price_down", "StockX压价任务"),
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
