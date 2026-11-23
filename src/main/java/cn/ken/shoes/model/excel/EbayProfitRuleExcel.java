package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** eBay 盈利改价：按货号/物品编号（可细到尺码）指定目标盈利，或填「不动」跳过改价。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EbayProfitRuleExcel {

    @ExcelProperty(value = "货号或物品编号", index = 0)
    private String target;

    @ExcelProperty(value = "尺码(EU，空=全尺码)", index = 1)
    private String size;

    @ExcelProperty(value = "目标盈利(元，或填不动)", index = 2)
    private String targetProfit;
}
