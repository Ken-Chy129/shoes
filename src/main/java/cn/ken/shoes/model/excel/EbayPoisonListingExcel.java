package cn.ken.shoes.model.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** eBay 得物价上架：只需填写货号，得物有价的尺码全部上架。 */
@Data
public class EbayPoisonListingExcel {

    @ExcelProperty(value = "货号（例：DD1391-100）", index = 0)
    private String styleId;
}
