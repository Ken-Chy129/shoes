package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("no_price_model")
public class NoPriceModelDO {

    private String modelNo;
}
