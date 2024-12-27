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
}
