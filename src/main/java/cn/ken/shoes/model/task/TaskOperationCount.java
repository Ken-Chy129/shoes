package cn.ken.shoes.model.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Task-level successful StockX operation counts aggregated from task_item.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskOperationCount {

    private Long taskId;

    private Long priceDownCount;

    private Long listingCount;

    private Long delistCount;

    private Long pendingOperationCount;
}
