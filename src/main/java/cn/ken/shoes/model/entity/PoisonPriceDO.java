package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("poison_price")
public class PoisonPriceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelNo;

    private String euSize;

    /**
     * 普通发货
     */
    private Integer normalPrice;

    /**
     * 闪电发货
     */
    private Integer lightningPrice;

    /**
     * 极速发货
     */
    private Integer fastPrice;

    /**
     * 品牌直发
     */
    private Integer brandPrice;
}
