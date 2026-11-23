package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/** eBay 特殊运费导入：货号或物品编号、尺码（空=全尺码）、AG 运费。 */
@Data
public class EbaySpecialFreightExcel {

    @ExcelProperty(value = "货号或物品编号", index = 0)
    private String target;

    @ExcelProperty(value = "尺码(EU，空=全尺码)", index = 1)
    private String size;

    @ExcelProperty(value = "运费(元，AG链接)", index = 2)
    private BigDecimal freight;
}
