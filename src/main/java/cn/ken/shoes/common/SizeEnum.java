package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SizeEnum {

    MEN_US("US(M)"),
    WOMAN_US("US(W)"),
    US("US"),
    UK("UK"),
    EU("EU"),
    CM("CM"),
    UNKNOWN("unknown"),;

    private final String code;

    public static SizeEnum from(String code) {
        for (SizeEnum sizeEnum : SizeEnum.values()) {
            if (sizeEnum.code.equals(code)) {
                return sizeEnum;
            }
        }
        return UNKNOWN;
    }
}
