package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/6/15
 */
@Data
public class StockXOrderExcel {

    @ExcelProperty("商品")
    private String title;

    @ExcelProperty("货号名称")
    private String name;

    @ExcelProperty("货号尺码")
    private String usSize;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("订单号")
    private String orderNumber;

    @ExcelProperty("销售价格")
    private Integer usd;

    @ExcelProperty("价格币种")
    private Integer rmb;

    @ExcelProperty("出售日期")
    private String soldOn;

    @ExcelProperty("EU码")
    private String euSize;

}
