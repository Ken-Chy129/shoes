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

    /** 可选：直接指定 StockX variantId（服饰等无法按尺码匹配时使用），填写后尺码可留空。 */
    @ExcelProperty("variantId")
    private String variantId;
}
