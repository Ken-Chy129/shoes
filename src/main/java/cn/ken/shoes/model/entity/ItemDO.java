package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("item")
@EqualsAndHashCode(callSuper = true)
public class ItemDO extends BaseDO {

    /**
     * 名称
     */
    private String name;

    /**
     * 货号
     */
    private String modelNumber;

    /**
     * 品牌名称
     */
    private String brandName;

    /**
     * 图片
     */
    private String image;

    /**
     * 类型
     */
    private String productType;

}

