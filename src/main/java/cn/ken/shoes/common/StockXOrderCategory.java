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
    ;

    private final String code;
    private final String label;
    private final String displayStatus;
    private final List<String> listingStatuses;
    private final List<String> orderStatuses;

    public static Optional<StockXOrderCategory> fromCode(String code) {
        for (StockXOrderCategory category : values()) {
            if (category.code.equals(code)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
