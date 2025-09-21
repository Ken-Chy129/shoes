package cn.ken.shoes.common;

import lombok.Getter;

@Getter
public enum StockXSortEnum {

    FEATURED("featured", "精选"),
    MOST_ACTIVE("most-active", "Top Selling"),
    LOWEST_ASK("lowest_ask", "Price: Low to High"),
    HIGHEST_BID("highest_bid", "出价: 从高到低"),
    RECENT_BIDS("recent_bids", "Recent High Bids"),
    RECENT_ASKS("recent_asks", "Recent Price Drops"),
    DEADSTOCK_SOLD("deadstock_sold", "Total Sold: High to Low"),
    RELEASE_DATE("release_date", "发布日期"),
    PRICE_PREMIUM("price_premium", "Price Premium: High to Low"),
    LAST_SALE("last_sale", "Last Sale: High to Low"),
    ;

    private final String code;
    private final String desc;

    StockXSortEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static StockXSortEnum from(String code) {
        for (StockXSortEnum stockXSortEnum : values()) {
            if (stockXSortEnum.code.equals(code)) {
                return stockXSortEnum;
            }
        }
        return null;
    }
}
