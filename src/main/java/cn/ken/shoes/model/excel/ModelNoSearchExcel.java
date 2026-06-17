package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class ModelNoSearchExcel {

    @ExcelProperty("货号")
    private String modelNo;
}
