package cn.ken.shoes.model.entity;

import com.alibaba.excel.annotation.ExcelProperty;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("poison_price")
public class PoisonPriceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("EU码")
    private String euSize;

    @ExcelProperty("价格")
    private Integer price;

    @ExcelProperty("版本")
    private Integer version;

}
