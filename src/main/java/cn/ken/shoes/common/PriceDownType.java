package cn.ken.shoes.common;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

public enum PriceDownType {
    DEFAULT("default", "默认"),
    POISON("poison", "得物"),
    POISON_35("poison_35", "得物3.5");

    private final String code;
    private final String label;

    PriceDownType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static PriceDownType fromExcelValue(String value) {
        if (StrUtil.isBlank(value)) {
            return DEFAULT;
        }
        String normalized = value.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "default", "默认" -> DEFAULT;
            case "poison", "得物" -> POISON;
            case "poison_35", "poison35", "得物3.5", "得物３.５" -> POISON_35;
            default -> throw new IllegalArgumentException(
                    "不支持的压价类型：" + value + "，可选值为：默认、得物、得物3.5");
        };
    }

    public static PriceDownType fromCode(String code) {
        return fromExcelValue(code);
    }
}
