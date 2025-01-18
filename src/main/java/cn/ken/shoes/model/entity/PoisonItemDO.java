package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


@Data
@TableName("poison_item")
public class PoisonItemDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spuId;

    private String brandName;

    private String categoryName;

    private String title;

    private String articleNumber;

    private String spuLogo;

    private Integer releaseYear;
}
