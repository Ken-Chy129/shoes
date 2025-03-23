package cn.ken.shoes.common;

import lombok.Getter;

@Getter
public enum StockXSortEnum {

    FEATURED("featured", "精选"),
    MOST_ACTIVE("most-active", "最受欢迎"),
    RECENT_ASKS("recent_asks", "新最低报价"),
    RECENT_BIDS("recent_bids", "新最高出价"),
    AVERAGE_PRICE("average_deadstock_price", "平均成交价"),
    DEADSTOCK_SOLD("deadstock_sold", "总销量"),
    VOLATILITY("volatility", "价格波动性"),
    PRICE_PREMIUM("price_premium", "溢价"),
    LAST_SALE("last_sale", "最新售价"),
    LOWEST_ASK("lowest_ask", "最低报价"),
    HIGHEST_BID("highest_bid", "最高出价"),
    RELEASE_DATE("release_date", "发布日期"),
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
