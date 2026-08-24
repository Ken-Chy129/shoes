package cn.ken.shoes.model.ebay;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EbayProductMetadata {

    private String title;
    private String brand;
    private String description;
    private String productType;
    private String gender;
    private String color;
    private String colorway;
    private String upperMaterial;
    private List<String> imageUrls = new ArrayList<>();
}
