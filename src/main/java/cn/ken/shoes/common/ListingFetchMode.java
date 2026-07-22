package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ListingFetchMode {

    ALL("all"),
    EXCEL_SEARCH("excel_search");

    private final String code;

    public static ListingFetchMode fromCode(String code) {
        for (ListingFetchMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return ALL;
    }
}
