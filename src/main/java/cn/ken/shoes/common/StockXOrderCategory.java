package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum StockXOrderCategory {

    COMPLETED(
            "completed",
            "已完成",
            "销售完成",
            List.of("COMPLETED", "COMPLETED_LOST", "RETURN_COMPLETE", "RETURN_COMPLETED",
                    "BUYER_PROMISE_RETURN_REQUESTED", "BUYER_PROMISE_RETURN_PROCESSING",
                    "BUYER_PROMISE_RETURN_IN_TRANSIT", "BUYER_PROMISE_RETURN_APPROVED",
                    "BUYER_PROMISE_RETURN_COMPLETED"),
            List.of()),
    CANCELLED("cancelled", "已取消", "已取消", List.of("CANCELED"), List.of()),
    PENDING("pending", "待处理", "待处理", List.of(), List.of()),
    PENDING_PAYOUT(
            "pending_payout",
            "待付款",
            "待付款",
            List.of("MATCHED"),
            List.of("PAYOUTPENDING", "PAYOUTCOMPLETED", "PAYOUTFAILED")),
    SHIPMENT_PACKED("packed", "已打包", "已打包",
            List.of("READY_TO_SHIP"), List.of("CREATED")),
    SHIPMENT_SHIPPED("shipped", "已发货", "已发货",
            List.of("MATCHED", "READY_TO_SHIP", "IN_TRANSIT_TO_WAREHOUSE"), List.of("SHIPPED")),
    SHIPMENT_RECEIVED("received", "已收到", "已收到",
            List.of("MATCHED", "READY_TO_SHIP"),
            List.of("RECEIVED", "AUTHENTICATING", "AUTHENTICATED", "AUTHFAILED")),
    ;

    private final String code;
    private final String label;
    private final String displayStatus;
    private final List<String> listingStatuses;
    private final List<String> orderStatuses;

    public boolean isShipment() {
        return this == SHIPMENT_PACKED || this == SHIPMENT_SHIPPED || this == SHIPMENT_RECEIVED;
    }

    public static Optional<StockXOrderCategory> fromCode(String code) {
        for (StockXOrderCategory category : values()) {
            if (category.code.equals(code)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
