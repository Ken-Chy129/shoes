package cn.ken.shoes.common;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.model.entity.CustomModelDO;
import lombok.Getter;

import java.util.function.Consumer;

@Getter
public enum CustomPriceTypeEnum {

    THREE_FIVE(1, "得物3.5", ShoesContext::addThreeFiveModel),
    NOT_COMPARE(2, "不比价", ShoesContext::addNotCompareModel),
    NO_PRICE(3, "无价", ShoesContext::addNoPrice),
    FLAWS(4, "瑕疵", ShoesContext::addFlawsModel),
    ;

    private final int code;

    private final String desc;

    private final Consumer<CustomModelDO> cachePutConsumer;

    CustomPriceTypeEnum(int code, String desc, Consumer<CustomModelDO> cachePutConsumer) {
        this.code = code;
        this.desc = desc;
        this.cachePutConsumer = cachePutConsumer;
    }

    public static CustomPriceTypeEnum from(int code) {
        for (CustomPriceTypeEnum customPriceTypeEnum : CustomPriceTypeEnum.values()) {
            if (customPriceTypeEnum.getCode() == code) {
                return customPriceTypeEnum;
            }
        }
        return null;
    }

}
