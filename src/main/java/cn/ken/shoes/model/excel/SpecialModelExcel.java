package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class SpecialModelExcel {

    @ExcelProperty("类型")
    private String category;

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("尺码")
    private String euSize;
}
