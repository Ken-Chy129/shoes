package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Data
public class DunkPriceExcel {

    @ExcelProperty("型号")
    private String modelNo;

    @ExcelProperty("品牌")
    private String brand;

    @ExcelProperty("名称")
    private String title;

    @ExcelProperty("性别")
    private String gender;

    @ExcelProperty("编号")
    private Integer size;

    @ExcelProperty("尺码CM")
    private String sizeText;

    @ExcelProperty("最低价格")
    private Integer lowPrice;

    @ExcelProperty("最高求购价")
    private Integer highPrice;

    @ExcelProperty("卖家库存")
    private Integer inventory;

    @ExcelProperty("求购数量")
    private Integer buyCount;

    @ExcelProperty("最近销量")
    private String lastSales;
}
