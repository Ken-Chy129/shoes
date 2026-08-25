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
    private String modelName;
    private String productLine;
    private String countryOfOrigin;
    private String gender;
    private String color;
    private String colorway;
    private String upperMaterial;
    private boolean manualTitle;
    private List<String> imageUrls = new ArrayList<>();
}
