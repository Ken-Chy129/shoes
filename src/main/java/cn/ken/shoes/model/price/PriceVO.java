package cn.ken.shoes.model.price;

import lombok.Data;

@Data
public class PriceVO {

    private String euSize;

    private Integer businessPrice;

    private Integer latestPrice;

    private Integer priceDiff;

    private String cacheTime;

    private String remark;
}
