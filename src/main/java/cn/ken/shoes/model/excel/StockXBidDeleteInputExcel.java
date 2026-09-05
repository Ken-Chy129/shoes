package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class StockXBidDeleteInputExcel {

    @ExcelProperty("货号")
    private String styleId;
}
