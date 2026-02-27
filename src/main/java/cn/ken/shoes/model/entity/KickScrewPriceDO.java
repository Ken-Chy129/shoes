package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("kick_screw_price")
public class KickScrewPriceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelNo;

    private String euSize;

    private Integer price;

    /**
     * 平台最低价
     */
    @TableField(exist = false)
    private Integer lowestPrice;

    /**
     * 是否为最低价
     */
    @TableField(exist = false)
    private Boolean isLowestPrice;
}
