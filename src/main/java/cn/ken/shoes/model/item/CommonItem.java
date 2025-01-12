package cn.ken.shoes.model.item;

import lombok.Data;

@Data
public class CommonItem {

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
