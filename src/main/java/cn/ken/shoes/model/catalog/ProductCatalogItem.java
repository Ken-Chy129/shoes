package cn.ken.shoes.model.catalog;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class ProductCatalogItem {

    private String modelNo;
    private String title;
    private String brand;
    private String description;
    private String productType;
    private String gender;
    private String color;
    private String colorway;
    private String upperMaterial;
    private List<String> imageUrls = new ArrayList<>();
    private int imageCount;
    private String source;
    private Date sourceUpdatedAt;
    private boolean manualOverride;
    private Date gmtCreate;
    private Date gmtModified;
}
