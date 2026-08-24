package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EbayListingExcel {

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("数量")
    private Integer quantity;

    @ExcelProperty("上架价格(USD)")
    private BigDecimal price;

    @ExcelProperty("标题(选填)")
    private String title;

    @ExcelProperty("品牌(选填)")
    private String brand;

    @ExcelProperty("描述(选填)")
    private String description;

    @ExcelProperty("图片链接(选填)")
    private String imageUrls;

    @ExcelProperty("颜色(选填)")
    private String color;

    @ExcelProperty("配色(选填)")
    private String colorway;

    @ExcelProperty("鞋面材质(选填)")
    private String upperMaterial;

    @ExcelProperty("性别(选填)")
    private String gender;

    @ExcelProperty("分类ID(选填)")
    private String categoryId;
}
