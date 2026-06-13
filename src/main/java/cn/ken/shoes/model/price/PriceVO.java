package cn.ken.shoes.model.price;

import lombok.Data;

@Data
public class PriceVO {

    private String euSize;

    private Integer businessPrice;

    private Integer latestPrice;

    private String remark;
}
