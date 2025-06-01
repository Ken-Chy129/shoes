package cn.ken.shoes.model.setting;

import lombok.Data;

@Data
public class PriceSetting {

    /**
     * 汇率（美元->人民币）
     */
    private Double exchangeRate;

    /**
     * 运费
     */
    private Integer freight;

    /**
     * KC到手比例
     */
    public Double kcGetRate;

    /**
     * KC服务费
     */
    public Integer kcServiceFee;
}
