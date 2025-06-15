package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/6/15
 */
@Data
public class StockXOrderExcel {

    @ExcelProperty("名称")
    private String title;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("订单号")
    private String orderNumber;

    @ExcelProperty("销售价格")
    private Integer amount;

    @ExcelProperty("出售日期")
    private String soldOn;

    @ExcelProperty("发货截止日期")
    private String dateToShipBy;

    @ExcelProperty("状态")
    private Integer state;

    @ExcelProperty("欧码")
    private String euSize;

    @ExcelProperty("美码")
    private String usSize;
}
