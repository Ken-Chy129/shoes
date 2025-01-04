package cn.ken.shoes.model.entity;

import cn.ken.shoes.model.poinson.Sku;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.List;

@Data
@TableName("poison_item")
public class PoisonItemDO {

    private Long spuId;

    private String brandName;

    private String categoryName;

    private String title;

    private String articleNumber;

    private String spuLogo;

    private String kcBrand;
}
