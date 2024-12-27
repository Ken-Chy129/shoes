package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PriceEnum {

    NORMAL(0),
    LIGHTNING(1),
    FAST(2);

    private final Integer code;

    public static PriceEnum from(Integer code) {
        for (PriceEnum priceEnum : PriceEnum.values()) {
            if (priceEnum.getCode().equals(code)) {
                return priceEnum;
            }
        }
        return NORMAL;
    }
}
