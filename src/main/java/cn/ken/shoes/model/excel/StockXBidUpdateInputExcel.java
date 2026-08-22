package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockXBidUpdateInputExcel {

    @ExcelProperty("出价ID")
    private String bidId;

    @ExcelProperty("价格")
    private BigDecimal price;
}
