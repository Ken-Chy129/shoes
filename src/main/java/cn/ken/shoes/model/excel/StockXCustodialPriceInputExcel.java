package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockXCustodialPriceInputExcel {
    @ExcelProperty("listingId")
    private String listingId;
    @ExcelProperty("价格")
    private BigDecimal price;
}
