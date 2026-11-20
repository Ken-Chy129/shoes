package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * eBay 指定尺码改库存的输入行。按表头名读取（不按列位置），新库存为空的行忽略；
 * 「获取刊登」导出表里没有「新库存」列，误传不会把价格当成库存。
 */
@Data
public class EbaySetQuantityExcel {

    @ExcelProperty("物品编号")
    private String itemId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("新库存")
    private String quantity;

    @ExcelProperty("SKU")
    private String sku;
}
