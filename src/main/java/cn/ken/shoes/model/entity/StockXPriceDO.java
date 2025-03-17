package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("stockx_price")
public class StockXPriceDO {

    private String variantId;

    private String productId;

    private String euSize;

    private Integer sellFasterAmount;

    private Integer earnMoreAmount;
}
