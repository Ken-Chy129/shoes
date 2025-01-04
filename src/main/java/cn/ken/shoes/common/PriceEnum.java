package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PriceEnum {

    NORMAL(0, "普通发货"),
    LIGHTNING(1, "闪电直发"),
    FAST(2, "极速发货");

    private final Integer code;
    private final String desc;

    public static PriceEnum from(Integer code) {
        for (PriceEnum priceEnum : PriceEnum.values()) {
            if (priceEnum.getCode().equals(code)) {
                return priceEnum;
            }
        }
        return NORMAL;
    }

    public static PriceEnum from(String desc) {
        for (PriceEnum priceEnum : PriceEnum.values()) {
            if (priceEnum.getDesc().equals(desc)) {
                return priceEnum;
            }
        }
        return NORMAL;
    }
}
