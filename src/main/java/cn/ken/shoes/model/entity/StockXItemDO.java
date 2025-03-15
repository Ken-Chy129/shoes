package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("stockx_item")
public class StockXItemDO {

    private String productId;

    private String brand;

    private String productType;

    private String modelNo;

    private String urlKey;

    private String title;

    private String releaseDate;
}
