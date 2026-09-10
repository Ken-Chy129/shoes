package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** StockX 买家侧「获取订单 / 获取历史记录」任务明细导出。 */
@Data
public class StockXPurchaseOrderExcel {

    @ExcelProperty("订单号")
    private String orderNumber;

    @ExcelProperty("chainId")
    private String chainId;

    @ExcelProperty("variantId")
    private String variantId;

    @ExcelProperty("品牌")
    private String brand;

    @ExcelProperty("产品名称")
    private String title;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("US码")
    private String size;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("购买价格")
    private String purchasePrice;

    @ExcelProperty("订单状态")
    private String orderStatus;

    @ExcelProperty("购买时间")
    private String purchaseTime;
}
