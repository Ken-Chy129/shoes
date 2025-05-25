package cn.ken.shoes.common;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.model.entity.CustomModelDO;
import lombok.Getter;

import java.util.function.Consumer;

@Getter
public enum CustomPriceTypeEnum {

    THREE_FIVE(1, "得物3.5", ShoesContext::addThreeFiveModel, ShoesContext::clearThreeFiveModelSet),
    NOT_COMPARE(2, "不比价", ShoesContext::addNotCompareModel, ShoesContext::clearNotCompareModelSet),
    NO_PRICE(3, "无价", ShoesContext::addNoPrice, ShoesContext::clearNoPriceModelSet),
    FLAWS(4, "瑕疵", ShoesContext::addFlawsModel, ShoesContext::clearFlawsModelSet),
    ;

    private final int code;

    private final String desc;

    private final Consumer<CustomModelDO> cachePutConsumer;

    private final Runnable clearRunnable;

    CustomPriceTypeEnum(int code, String desc, Consumer<CustomModelDO> cachePutConsumer, Runnable clearRunnable) {
        this.code = code;
        this.desc = desc;
        this.cachePutConsumer = cachePutConsumer;
        this.clearRunnable = clearRunnable;
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
