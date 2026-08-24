package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("product_catalog")
public class ProductCatalogDO {

    @TableId(value = "model_no", type = IdType.INPUT)
    private String modelNo;
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
    private String imageUrls;
    private String source;
    private Date sourceUpdatedAt;
    private Boolean manualOverride;
    private Date gmtCreate;
    private Date gmtModified;
}
