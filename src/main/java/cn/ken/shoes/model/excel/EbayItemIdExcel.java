package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** eBay 按编号下架 / 重新上架的输入行：只读第一列物品编号，其余列忽略（可直接上传「获取刊登」导出的表）。 */
@Data
public class EbayItemIdExcel {

    @ExcelProperty(value = "物品编号", index = 0)
    private String itemId;
}
