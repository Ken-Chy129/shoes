package cn.ken.shoes.model.price;

import cn.ken.shoes.model.entity.PoisonPriceDO;
import lombok.Data;

@Data
public class PriceVO {

    private String modelNo;

    private String size;

    private Integer poisonPrice;

    private Double kcPrice;

    private Double stockxPrice;

    private Double kcEarn;

    private Double stockxEarn;

    public static PriceVO build(PoisonPriceDO poisonPriceDO) {
        PriceVO priceVO = new PriceVO();
        priceVO.setModelNo(poisonPriceDO.getModelNo());
        priceVO.setSize(poisonPriceDO.getEuSize());
        priceVO.setPoisonPrice(poisonPriceDO.getPrice());
        return priceVO;
    }
}
