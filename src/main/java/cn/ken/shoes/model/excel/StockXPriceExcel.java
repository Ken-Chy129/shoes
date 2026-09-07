package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @author Ken-Chy129
 * @date 2025/6/15
 */
@Data
public class StockXPriceExcel {

    @ExcelProperty("品牌")
    private String brand;

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

    @ExcelProperty("US女码")
    private String uswSize;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("绿叉价格")
    private Integer price;

    private Integer standardPrice;

    private Integer flexPrice;

    @ExcelProperty("绿叉求购价")
    private Integer purchasePrice;

    @ExcelProperty("绿叉72小时销量")
    private Integer last72HoursSales;

    @ExcelProperty("绿叉90天销量")
    private Integer last90DaysSales;

    private BigDecimal averagePrice7Days;

    private BigDecimal medianPrice7Days;

    private Integer salesCount7Days;

    private BigDecimal averagePrice30Days;

    private BigDecimal medianPrice30Days;

    private Integer salesCount30Days;

    private BigDecimal averagePrice90Days;

    private BigDecimal medianPrice90Days;

    private Integer salesCount90Days;

    @ExcelProperty("得物价格")
    private Integer poisonPrice;

}
