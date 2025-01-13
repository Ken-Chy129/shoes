package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("poison_price")
public class PoisonPriceDO {

    private String modelNumber;

    private String euSize;

    private String price;
}
