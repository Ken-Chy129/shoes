package cn.ken.shoes.common;

import cn.ken.shoes.model.entity.PoisonPriceDO;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.function.Function;

@Getter
@AllArgsConstructor
public enum PriceEnum {

    /**
     * 普通发货
     */
    NORMAL(0, List.of("NORMAL", "普通发货"), PoisonPriceDO::getNormalPrice),
    /**
     * 闪电直发
     */
    LIGHTNING(1, List.of("LIGHTNING", "闪电直发"), PoisonPriceDO::getLightningPrice),

    FAST(2, List.of("极速发货"), PoisonPriceDO::getFastPrice),

    BRAND(3, List.of("品牌直发"), PoisonPriceDO::getBrandPrice),

    ;

    private final Integer code;
    private final List<String> desc;
    private final Function<PoisonPriceDO, Integer> getPriceFunction;

    public static PriceEnum from(Integer code) {
        for (PriceEnum priceEnum : PriceEnum.values()) {
            if (priceEnum.getCode().equals(code)) {
                return priceEnum;
            }
        }
        return LIGHTNING;
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
