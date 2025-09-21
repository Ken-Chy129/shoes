package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/6/15
 */
@Data
public class StockXPriceExcel {

    @ExcelProperty("产品名称")
    private String title;

    @ExcelProperty("id")
    private String id;

    @ExcelProperty("uk")
    private String uk;

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("us码")
    private String usmSize;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("绿叉价格")
    private Integer price;

    @ExcelProperty("绿叉求购价")
    private Integer purchasePrice;

    @ExcelProperty("绿叉72小时销量")
    private Integer last72HoursSales;

    @ExcelProperty("得物价格")
    private Integer poisonPrice;

}
