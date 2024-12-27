package cn.ken.shoes.model;

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
     * 尺码-价格
     */
    private List<SizePrice> sizePrices;

}
