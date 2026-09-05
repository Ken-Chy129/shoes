package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Locale;

/**
 * StockX Pro「购买」任务支持的操作。
 */
@Getter
@AllArgsConstructor
public enum StockXPurchaseOperation {

    BIDS("bids", "获取出价", "Bids", "CURRENT"),
    ORDERS("orders", "获取订单", "Buying", "PENDING"),
    HISTORY("history", "获取历史记录", "Buying", "HISTORICAL"),
    CREATE_BIDS("create_bids", "创建出价", null, null),
    UPDATE_BIDS("update_bids", "修改出价", null, null),
    DELETE_BIDS("delete_bids", "撤销出价", null, null),
    ;

    private final String code;
    private final String label;
    private final String operationName;
    private final String state;

    public static StockXPurchaseOperation fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (StockXPurchaseOperation operation : values()) {
            if (operation.code.equals(normalized)
                    || operation.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return operation;
            }
        }
        return null;
    }
}
