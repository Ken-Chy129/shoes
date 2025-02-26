package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class ModelExcel {

    @ExcelProperty("货号")
    private String modelNo;
}
