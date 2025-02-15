package cn.ken.shoes.model.shoes;

import lombok.Data;

@Data
public class ShoesVO {

    private String modelNumber;

    private String name;

    private String brand;

    private String pic;

    private String releaseYear;

    private String size;

    private String normalPrice;

    private String lightningPrice;

    private String kcPrice;
}
