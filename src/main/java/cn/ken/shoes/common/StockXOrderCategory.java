package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Optional;

@Getter
@AllArgsConstructor
public enum StockXOrderCategory {

    COMPLETED(TaskTypeEnum.FETCH_COMPLETED_ORDERS, true, "COMPLETED", "销售完成"),
    CANCELLED(TaskTypeEnum.FETCH_CANCELLED_ORDERS, true, "CANCELED", "已取消"),
    PENDING_PAYOUT(TaskTypeEnum.FETCH_PENDING_PAYOUT_ORDERS, false, "PAYOUTPENDING", "待付款"),
    ;

    private final TaskTypeEnum taskType;
    private final boolean historical;
    private final String orderStatus;
    private final String displayStatus;

    public static Optional<StockXOrderCategory> fromTaskType(TaskTypeEnum taskType) {
        for (StockXOrderCategory category : values()) {
            if (category.taskType == taskType) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    public static Optional<StockXOrderCategory> fromTaskTypeCode(String taskTypeCode) {
        return fromTaskType(TaskTypeEnum.fromCode(taskTypeCode));
    }

    public static String displayStatus(String status) {
        if (status == null) {
            return null;
        }
        for (StockXOrderCategory category : values()) {
            if (category.orderStatus.equals(status)) {
                return category.displayStatus;
            }
        }
        return status;
    }
}
