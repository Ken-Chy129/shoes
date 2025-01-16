package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public enum PriceEnum {

    /**
     * 普通发货
     */
    NORMAL(0, List.of("NORMAL", "普通发货")),
    /**
     * 闪电直发
     */
    LIGHTNING(1, List.of("LIGHTNING", "闪电直发")),
//    FAST(2, "极速发货")
    ;

    private final Integer code;
    private final List<String> desc;

    public static PriceEnum from(Integer code) {
        for (PriceEnum priceEnum : PriceEnum.values()) {
            if (priceEnum.getCode().equals(code)) {
                return priceEnum;
            }
        }
        return null;
    }

    public static PriceEnum from(String desc) {
        for (PriceEnum priceEnum : PriceEnum.values()) {
            if (priceEnum.getDesc().contains(desc)) {
                return priceEnum;
            }
        }
        return null;
    }
}
