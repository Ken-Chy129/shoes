package cn.ken.shoes.model.ebay;

import java.util.Locale;

/**
 * eBay 上架的发货区域：决定库存地点（发货地址）和业务政策（运输策略）。
 * HK 沿用历史默认配置，US 使用 ebay.us.* 配置。
 */
public enum EbayListingRegion {
    HK("hk", "港区"),
    US("us", "美区");

    private final String code;
    private final String label;

    EbayListingRegion(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 空值视为港区，兼容旧任务和旧前端。 */
    public static EbayListingRegion fromCode(String value) {
        if (value == null || value.isBlank()) {
            return HK;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (EbayListingRegion region : values()) {
            if (region.code.equals(normalized)) {
                return region;
            }
        }
        throw new IllegalArgumentException("不支持的发货区域：" + value);
    }
}
