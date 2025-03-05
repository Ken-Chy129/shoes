package cn.ken.shoes.model.price;

import cn.ken.shoes.model.entity.PoisonPriceDO;
import lombok.Data;

import java.util.Optional;

@Data
public class PriceVO {

    private String modelNo;

    private String size;

    private Integer normalPrice;

    private Integer lightningPrice;

    private Integer fastPrice;

    private Integer brandPrice;

    private Double kcPrice;

    private Double stockxPrice;

    private Double kcEarn;

    private Double stockxEarn;

    public static PriceVO build(PoisonPriceDO poisonPriceDO) {
        PriceVO priceVO = new PriceVO();
        priceVO.setModelNo(poisonPriceDO.getModelNo());
        priceVO.setSize(poisonPriceDO.getEuSize());
        Optional.ofNullable(poisonPriceDO.getNormalPrice()).ifPresent(price -> priceVO.setNormalPrice(price / 100));
        Optional.ofNullable(poisonPriceDO.getLightningPrice()).ifPresent(price -> priceVO.setLightningPrice(price / 100));
        Optional.ofNullable(poisonPriceDO.getFastPrice()).ifPresent(price -> priceVO.setFastPrice(price / 100));
        Optional.ofNullable(poisonPriceDO.getBrandPrice()).ifPresent(price -> priceVO.setBrandPrice(price / 100));
        return priceVO;
    }
}
