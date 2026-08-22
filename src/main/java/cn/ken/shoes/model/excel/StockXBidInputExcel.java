package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockXBidInputExcel {

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("价格")
    private BigDecimal price;
}
