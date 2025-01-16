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

    private Integer normalPrice;

    private Integer lightningPrice;


}
