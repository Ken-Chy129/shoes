package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


@Data
@TableName("poison_item")
public class PoisonItemDO {

    private Long spuId;

    private String brandName;

    private String categoryName;

    private String title;

    private String articleNumber;

    private String spuLogo;

    private Integer release_year;
}
