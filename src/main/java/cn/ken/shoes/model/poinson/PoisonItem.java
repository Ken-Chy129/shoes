package cn.ken.shoes.model.poinson;

import lombok.Data;

import java.util.List;

@Data
public class PoisonItem {

    private Long spuId;

    private Long brandId;

    private Long categoryId;

    private String brandName;

    private String categoryName;

    private String title;

    private String articleNumber;

    private String otherNumbers;

    private String spuLogo;

    private Integer authPrice;

    private Integer status;

    private Integer supportCross;

    private Boolean hasAuth;

    private List<Sku> skus;
}
