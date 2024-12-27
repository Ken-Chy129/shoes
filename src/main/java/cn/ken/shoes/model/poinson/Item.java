package cn.ken.shoes.model.poinson;

import lombok.Data;

import java.util.List;

@Data
public class Item {

    private Long spuId;

    private Long brandId;

    private Long categoryId;

    private String brandName;

    private String categoryName;

    private String title;

    private String articleName;

    private String otherNumber;

    private String spuLogo;

    private Integer authPrice;

    private Integer status;

    private Integer supportCross;

    private Boolean hasAuth;

    private List<Sku> skus;
}
