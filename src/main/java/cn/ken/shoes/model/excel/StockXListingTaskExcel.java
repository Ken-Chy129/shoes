package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Getter;
import lombok.Setter;

/** 寄存导出保留通用任务列，并附加购买来源；不改变其他任务的导出。 */
@Getter
@Setter
public class StockXListingTaskExcel extends TaskItemExcel {
    @ExcelProperty("原购买订单号")
    private String purchaseOrderNumber;

    @ExcelProperty("购买价格")
    private String purchasePrice;
}
