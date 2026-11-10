package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * KC 关键词搜索结果：每个货号一行。
 */
@Data
public class KickScrewSearchExcel {

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("品牌")
    private String brand;

    @ExcelProperty("产品名称")
    private String title;

    @ExcelProperty("商品类型")
    private String productType;

    @ExcelProperty("是否有货")
    private String available;

    @ExcelProperty("链接")
    private String url;
}
