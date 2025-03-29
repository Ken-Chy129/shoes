package cn.ken.shoes.common;

import cn.ken.shoes.model.entity.StockXPriceDO;
import lombok.Getter;

import java.util.function.Function;

@Getter
public enum StockXPriceEnum {

    SELL_FASTER("sellFaster", "更快售出", StockXPriceDO::getSellFasterAmount),
    EARN_MORE("earnMore", "挣得更多", StockXPriceDO::getEarnMoreAmount),
    SELL_NOW("sellNow", "立即售出", StockXPriceDO::getSellNowAmount),
    ;

    private final String code;
    private final String desc;
    private final Function<StockXPriceDO, Integer> priceFunction;

    StockXPriceEnum(String code, String desc, Function<StockXPriceDO, Integer> priceFunction) {
        this.code = code;
        this.desc = desc;
        this.priceFunction = priceFunction;
    }

    public static StockXPriceEnum from(String code) {
        for (StockXPriceEnum e : StockXPriceEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }
}
