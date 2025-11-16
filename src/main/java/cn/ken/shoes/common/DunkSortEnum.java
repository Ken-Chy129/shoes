package cn.ken.shoes.common;

import lombok.Getter;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Getter
public enum DunkSortEnum {

    RECOMMEND("recommend", "推荐"),
    HOTTEST("hottest", "人气"),
    LATEST("latest", "新到货订单"),
    PRICE_LOW("price_low", "按最低价排序"),
    PRICE_HIGH("price_high", "按最高价排序"),
    LAUNCH("launch", "按发售日期排序"),
    FAVORITE("favorite", "最喜欢的订单"),
    ;

    private final String code;
    private final String desc;

    DunkSortEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DunkSortEnum from(String code) {
        for (DunkSortEnum dunkSortEnum : values()) {
            if (dunkSortEnum.code.equals(code)) {
                return dunkSortEnum;
            }
        }
        return null;
    }

}
