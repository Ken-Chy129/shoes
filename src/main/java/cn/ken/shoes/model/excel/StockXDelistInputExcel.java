package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class StockXDelistInputExcel {

    @ExcelProperty("listingId")
    private String listingId;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;
}
