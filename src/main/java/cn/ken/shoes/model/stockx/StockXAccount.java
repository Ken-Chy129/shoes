package cn.ken.shoes.model.stockx;

import lombok.Data;

@Data
public class StockXAccount {

    private String name;

    private String apiKey;

    private String authorization;

    private boolean enabled;

    private String country = "US";

    private double transferFeeRate = 0.03;

    private double merchantFeeRate = 0.07;

    private double minMerchantFee = 5.79;

    private double platformShippingFee = 0;

    private int freight = 25;

    private int minProfit = -30;

    private long standardInterval = 1800;

    private long custodialInterval = 1800;
}
