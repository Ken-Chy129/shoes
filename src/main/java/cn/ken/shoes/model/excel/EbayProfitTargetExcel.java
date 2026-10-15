package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/** eBay 盈利改价：按货号单独指定的目标盈利（人民币）。 */
@Data
public class EbayProfitTargetExcel {

    @ExcelProperty(value = "货号（例：DD1391-100）", index = 0)
    private String styleId;

    @ExcelProperty(value = "目标盈利（人民币，如50）", index = 1)
    private BigDecimal targetProfit;
}
