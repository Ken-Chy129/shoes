package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("custom_model")
public class CustomModelDO {

    private String modelNo;

    private String euSize;

    private Integer type;
}
