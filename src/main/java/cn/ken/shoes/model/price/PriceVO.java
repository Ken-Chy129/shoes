package cn.ken.shoes.model.price;

import lombok.Data;

@Data
public class PriceVO {

    private String euSize;

    private Integer cachedPrice;

    private String cacheTime;

    private Integer latestPrice;

    private Integer businessPrice;

    private String remark;
}
