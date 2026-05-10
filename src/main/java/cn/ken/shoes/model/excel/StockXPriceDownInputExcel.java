package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class StockXPriceDownInputExcel {

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("最低价($)")
    private Integer minPrice;
}
