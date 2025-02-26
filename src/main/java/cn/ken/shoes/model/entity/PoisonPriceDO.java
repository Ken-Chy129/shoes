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

    /**
     * 普通发货
     */
    @ExcelProperty("普通发货")
    private Integer normalPrice;

    /**
     * 闪电发货
     */
    @ExcelProperty("闪电发货")
    private Integer lightningPrice;

    /**
     * 极速发货
     */
    @ExcelProperty("极速发货")
    private Integer fastPrice;

    /**
     * 品牌直发
     */
    @ExcelProperty("品牌直发")
    private Integer brandPrice;
}
