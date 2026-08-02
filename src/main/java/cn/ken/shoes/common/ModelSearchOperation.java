package cn.ken.shoes.common;

import java.util.Locale;

public enum ModelSearchOperation {
    FETCH_PRICE("fetch_price", "获取最低价"),
    CREATE_LISTING("create_listing", "按指定价格上架");

    private final String code;
    private final String desc;

    ModelSearchOperation(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ModelSearchOperation fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (ModelSearchOperation operation : values()) {
            if (operation.code.equals(normalized) || operation.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return operation;
            }
        }
        return null;
    }
}
