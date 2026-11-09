package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * eBay 指定尺码改价的输入行。列顺序与「获取刊登」导出表的前 4 列一致，填好新价格即可直接上传；
 * 新价格为空的行忽略。
 */
@Data
public class EbaySetPriceExcel {

    @ExcelProperty(value = "物品编号", index = 0)
    private String itemId;

    @ExcelProperty(value = "尺码", index = 1)
    private String size;

    @ExcelProperty(value = "新价格(USD)", index = 2)
    private String price;

    @ExcelProperty(value = "SKU", index = 3)
    private String sku;
}
