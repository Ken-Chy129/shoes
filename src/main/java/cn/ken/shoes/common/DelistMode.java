package cn.ken.shoes.common;

import java.util.Locale;

public enum DelistMode {
    EXCEL("excel", "Excel下架"),
    ALL("all", "全量下架");

    private final String code;
    private final String desc;

    DelistMode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static DelistMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (DelistMode mode : values()) {
            if (mode.code.equals(normalized) || mode.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
