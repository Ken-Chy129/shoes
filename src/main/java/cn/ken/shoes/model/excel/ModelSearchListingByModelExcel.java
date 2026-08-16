package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ModelSearchListingByModelExcel {

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("数量")
    private Integer quantity;

    @ExcelProperty("上架价格")
    private BigDecimal targetPrice;
}
