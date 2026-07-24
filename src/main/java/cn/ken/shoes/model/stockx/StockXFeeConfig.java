package cn.ken.shoes.model.stockx;

import lombok.Data;

@Data
public class StockXFeeConfig {

    private Double transferFeeRate;

    private Double merchantFeeRate;

    private Double minMerchantFee;

    private Double platformShippingFee;

    private Integer freight;

    private Integer minProfit;

    public StockXFeeConfig resolveWith(StockXFeeConfig defaults) {
        StockXFeeConfig resolved = new StockXFeeConfig();
        resolved.setTransferFeeRate(valueOrDefault(transferFeeRate, defaults.transferFeeRate));
        resolved.setMerchantFeeRate(valueOrDefault(merchantFeeRate, defaults.merchantFeeRate));
        resolved.setMinMerchantFee(valueOrDefault(minMerchantFee, defaults.minMerchantFee));
        resolved.setPlatformShippingFee(valueOrDefault(platformShippingFee, defaults.platformShippingFee));
        resolved.setFreight(valueOrDefault(freight, defaults.freight));
        resolved.setMinProfit(valueOrDefault(minProfit, defaults.minProfit));
        return resolved;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }
}
