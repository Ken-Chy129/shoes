package cn.ken.shoes.model.catalog;

import lombok.Data;

import java.util.List;

@Data
public class ProductCatalogUpdateRequest {

    private String title;
    private String brand;
    private String description;
    private String productType;
    private String gender;
    private String color;
    private String colorway;
    private String upperMaterial;
    private List<String> imageUrls;
}
