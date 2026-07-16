package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务类型枚举
 */
@Getter
@AllArgsConstructor
public enum TaskTypeEnum {

    LISTING("listing", "上架", true),
    PRICE_DOWN("price_down", "压价", true),
    FETCH_LISTINGS("fetch_listings", "获取上架商品", false),
    EXCEL_DELIST("excel_delist", "Excel下架", false),
    MODEL_SEARCH("model_search", "货号搜索上架", true),
    FETCH_COMPLETED_ORDERS("fetch_completed_orders", "获取已完成", false),
    FETCH_CANCELLED_ORDERS("fetch_cancelled_orders", "获取已取消", false),
    FETCH_PENDING_PAYOUT_ORDERS("fetch_pending_payout_orders", "获取待付款", false),
    ;

    private final String code;
    private final String desc;

    /**
     * 服务重启时是否自动重新拉起该类型的运行中任务
     */
    private final boolean resumeOnStartup;

    public static TaskTypeEnum fromCode(String code) {
        for (TaskTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
