package cn.ken.shoes.order;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.common.TaskTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXOrderCategoryTest {

    @Test
    void mapsTaskTypesToTheCorrectStockXOrderEndpointsAndStatuses() {
        assertThat(StockXOrderCategory.fromTaskType(TaskTypeEnum.FETCH_COMPLETED_ORDERS))
                .hasValueSatisfying(category -> {
                    assertThat(category.isHistorical()).isTrue();
                    assertThat(category.getOrderStatus()).isEqualTo("COMPLETED");
                });
        assertThat(StockXOrderCategory.fromTaskType(TaskTypeEnum.FETCH_CANCELLED_ORDERS))
                .hasValueSatisfying(category -> {
                    assertThat(category.isHistorical()).isTrue();
                    assertThat(category.getOrderStatus()).isEqualTo("CANCELED");
                });
        assertThat(StockXOrderCategory.fromTaskType(TaskTypeEnum.FETCH_PENDING_PAYOUT_ORDERS))
                .hasValueSatisfying(category -> {
                    assertThat(category.isHistorical()).isFalse();
                    assertThat(category.getOrderStatus()).isEqualTo("PAYOUTPENDING");
                });
    }
}
