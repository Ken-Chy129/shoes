package cn.ken.shoes.common;

import lombok.Getter;

@Getter
public enum CustomPriceTypeEnum {

    NORMAL(0, "普通价格"),
    THREE_FIVE(1, "得物3.5"),
    NOT_COMPARE(2, "不比价"),
    ;

    private final int code;

    private final String desc;

    CustomPriceTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
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
