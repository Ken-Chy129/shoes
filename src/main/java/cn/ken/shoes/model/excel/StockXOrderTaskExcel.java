package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class StockXOrderTaskExcel {

    @ExcelProperty("id")
    private String id;

    @ExcelProperty("商品id")
    private String productId;

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

    @ExcelProperty("截止日期")
    private String shipByDate;

    @ExcelProperty("StockX出售价格")
    private String salePrice;

    @ExcelProperty("得物价格")
    private String poisonPrice;

    @ExcelProperty("订单状态")
    private String status;

    @ExcelProperty("延期状态")
    private String extensionStatus;
}
