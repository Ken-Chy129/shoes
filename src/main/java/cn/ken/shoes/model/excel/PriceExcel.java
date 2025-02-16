package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class PriceExcel {

    @ExcelProperty("产品名称")
    private String productName;

    @ExcelProperty("id")
    private String id;

    @ExcelProperty("uk")
    private String uk;

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("绿叉价格")
    private String greenForkPrice;

    @ExcelProperty("绿叉求购价")
    private String greenForkPurchasePrice;

    @ExcelProperty("绿叉进口价")
    private String greenForkImportPrice;

    @ExcelProperty("3.5进口利润")
    private String importProfit35;

    @ExcelProperty("绿叉出口价")
    private String greenForkExportPrice;

    @ExcelProperty("绿叉72小时销量")
    private String greenForkSalesVolume72;

    @ExcelProperty("毒价格")
    private Integer poisonPrice;

    @ExcelProperty("3.5价格")
    private String price35;

    @ExcelProperty("3.5到手利润")
    private String profit35;

    @ExcelProperty("利润率")
    private String profitRate;

    @ExcelProperty("出货人数")
    private String deliveryPeopleCount;

    @ExcelProperty("求货人数")
    private String demandPeopleCount;

    @ExcelProperty("状态")
    private String status;
}
