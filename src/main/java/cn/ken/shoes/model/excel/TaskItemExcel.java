package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class TaskItemExcel {

    @ExcelProperty("轮次")
    private Integer round;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("当前价($)")
    private String currentPrice;

    @ExcelProperty("最低价($)")
    private String lowestPrice;

    @ExcelProperty("毒价格(¥)")
    private String poisonPrice;

    @ExcelProperty("3.5价格(¥)")
    private String poison35Price;

    @ExcelProperty("利润($)")
    private String profit35;

    @ExcelProperty("利润率")
    private String profitRate35;

    @ExcelProperty("操作结果")
    private String operateResult;

    @ExcelProperty("操作时间")
    private String operateTime;
}
