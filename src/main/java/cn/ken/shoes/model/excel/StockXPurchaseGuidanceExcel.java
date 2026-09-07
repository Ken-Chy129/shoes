package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class StockXPurchaseGuidanceExcel {
    @ExcelProperty("货号") private String styleId;
    @ExcelProperty("US码") private String size;
    @ExcelProperty("EU码") private String euSize;
    @ExcelProperty("产品名称") private String title;
    @ExcelProperty("variantId") private String variantId;
    @ExcelProperty("当前最高求购价") private BigDecimal highestBidPrice;
    @ExcelProperty("当前最低卖价") private BigDecimal lowestAskPrice;
    @ExcelProperty("Flex最低卖价") private BigDecimal flexLowestAskPrice;
    @ExcelProperty("最近成交价") private BigDecimal latestSalePrice;
    @ExcelProperty("最近成交时间") private Date latestSaleAt;
    @ExcelProperty("7天平均成交价") private BigDecimal averagePrice7d;
    @ExcelProperty("7天成交中位价") private BigDecimal medianPrice7d;
    @ExcelProperty("7天成交量") private Integer salesCount7d;
    @ExcelProperty("30天平均成交价") private BigDecimal averagePrice30d;
    @ExcelProperty("30天成交中位价") private BigDecimal medianPrice30d;
    @ExcelProperty("30天成交量") private Integer salesCount30d;
    @ExcelProperty("90天平均成交价") private BigDecimal averagePrice90d;
    @ExcelProperty("90天成交中位价") private BigDecimal medianPrice90d;
    @ExcelProperty("90天成交量") private Integer salesCount90d;
    @ExcelProperty("建议出价") private BigDecimal recommendedBid;
    @ExcelProperty("参考价格类型") private String referencePriceLabel;
    @ExcelProperty("参考成交价") private BigDecimal referenceSalePrice;
    @ExcelProperty("近期走势") private String salesTrend;
    @ExcelProperty("成交活跃度") private String salesActivity;
    @ExcelProperty("建议说明") private String reason;
}
