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
     * 平台抽成费率
     */
    private Double platformRate;

    /**
     * 最小利润率
     */
    private Double minProfitRate;

    /**
     * 最小利润
     */
    private Integer minProfit;
}
