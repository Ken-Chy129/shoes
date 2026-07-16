package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class StockXOrderTaskExcel {

    @ExcelProperty("id")
    private String id;

    @ExcelProperty("产品名称")
    private String title;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("订单号")
    private String orderNumber;

    @ExcelProperty("出售日期")
    private String soldOn;

    @ExcelProperty("StockX出售价格")
    private String salePrice;

    @ExcelProperty("状态")
    private String status;

    @ExcelProperty("货款")
    private String payoutAmount;
}
