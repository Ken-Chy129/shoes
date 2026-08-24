package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EbayListingTaskExcel {

    @ExcelProperty("SKU")
    private String sku;

    @ExcelProperty("Offer ID")
    private String offerId;

    @ExcelProperty("Listing ID")
    private String listingId;

    @ExcelProperty("品牌")
    private String brand;

    @ExcelProperty("标题")
    private String title;

    @ExcelProperty("货号")
    private String styleId;

    @ExcelProperty("尺码")
    private String size;

    @ExcelProperty("数量")
    private Integer quantity;

    @ExcelProperty("上架价格(USD)")
    private BigDecimal price;

    @ExcelProperty("执行结果")
    private String operateResult;

    @ExcelProperty("执行时间")
    private String operateTime;
}
