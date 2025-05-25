package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/5/25
 */
@Data
@TableName("special_price")
public class SpecialPriceDO {

    private String modelNo;

    private String euSize;

    private Integer price;
}
