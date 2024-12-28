package cn.ken.shoes.model.entity;

import lombok.Data;

import java.util.List;

@Data
public class Item {

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
