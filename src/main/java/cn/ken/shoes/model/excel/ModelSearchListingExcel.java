package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ModelSearchListingExcel {

    @ExcelProperty("variantId")
    private String variantId;

    @ExcelProperty("品牌")
    private String brand;

    @ExcelProperty("产品名称")
    private String title;

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("US码")
    private String usSize;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("现货最低价($)")
    private BigDecimal standardLowestPrice;

    @ExcelProperty("Flex最低价($)")
    private BigDecimal flexLowestPrice;

    @ExcelProperty("目标上架价($)")
    private BigDecimal targetPrice;

    @ExcelProperty("上架数量")
    private Integer quantity;

    @ExcelProperty("操作结果")
    private String operateResult;
}
