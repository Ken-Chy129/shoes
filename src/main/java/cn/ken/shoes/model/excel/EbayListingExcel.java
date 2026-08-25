package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EbayListingExcel {

    @ExcelProperty(value = "货号（例：DD1391-100）", index = 0)
    private String styleId;

    @ExcelProperty(value = "尺码（USM9/USW9/EU42.5）", index = 1)
    private String size;

    @ExcelProperty(value = "数量（整数）", index = 2)
    private Integer quantity;

    @ExcelProperty(value = "上架价格（USD，如199.99）", index = 3)
    private BigDecimal price;

    @ExcelProperty(value = "标题（选填；留空自动获取）", index = 4)
    private String title;

    @ExcelProperty(value = "品牌（选填；留空自动获取）", index = 5)
    private String brand;

    @ExcelProperty(value = "描述（选填；留空自动获取）", index = 6)
    private String description;

    @ExcelProperty(value = "图片链接（选填；多图用换行/分号）", index = 7)
    private String imageUrls;

    @ExcelProperty(value = "颜色（选填；如White）", index = 8)
    private String color;

    @ExcelProperty(value = "配色（选填；如White/Black）", index = 9)
    private String colorway;

    @ExcelProperty(value = "鞋面材质（选填；如Leather）", index = 10)
    private String upperMaterial;

    @ExcelProperty(value = "性别（选填；Men/Women/Unisex）", index = 11)
    private String gender;

    @ExcelProperty(value = "分类ID（选填；如15709）", index = 12)
    private String categoryId;
}
