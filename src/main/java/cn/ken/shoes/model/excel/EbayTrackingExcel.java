package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** eBay 添加追踪号码任务的输入行。 */
@Data
public class EbayTrackingExcel {

    @ExcelProperty(value = "订单编号（例：21-15259-42163）", index = 0)
    private String orderNumber;

    @ExcelProperty(value = "追踪号码（例：524382226900）", index = 1)
    private String trackingNumber;

    @ExcelProperty(value = "物流公司（选填；默认FedEx）", index = 2)
    private String carrier;
}
