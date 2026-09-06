package cn.ken.shoes.model.stockx;

import cn.hutool.core.util.StrUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public record StockXBidFeePolicy(boolean enabled,
                                 BigDecimal merchantFeeRate,
                                 BigDecimal minMerchantFee,
                                 BigDecimal transferFeeRate,
                                 boolean processOutsideExcel) {

    public static final BigDecimal DEFAULT_MERCHANT_FEE_RATE = new BigDecimal("0.07");
    public static final BigDecimal DEFAULT_MIN_MERCHANT_FEE = new BigDecimal("5.79");
    public static final BigDecimal DEFAULT_TRANSFER_FEE_RATE = new BigDecimal("0.03");

    public StockXBidFeePolicy {
        merchantFeeRate = valueOrDefault(merchantFeeRate, DEFAULT_MERCHANT_FEE_RATE);
        minMerchantFee = valueOrDefault(minMerchantFee, DEFAULT_MIN_MERCHANT_FEE);
        transferFeeRate = valueOrDefault(transferFeeRate, DEFAULT_TRANSFER_FEE_RATE);
        validateRate(merchantFeeRate, "商家手续费率");
        validateRate(transferFeeRate, "转账费率");
        if (minMerchantFee.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("最低商家手续费必须大于等于0");
        }
    }

    public static StockXBidFeePolicy disabled() {
        return new StockXBidFeePolicy(false, null, null, null, false);
    }

    public static StockXBidFeePolicy monitored(boolean processOutsideExcel) {
        return new StockXBidFeePolicy(true, null, null, null, processOutsideExcel);
    }

    public BigDecimal merchantFee(BigDecimal listingPrice) {
        requireListingPrice(listingPrice);
        BigDecimal percentageFee = listingPrice.multiply(merchantFeeRate);
        return percentageFee.max(minMerchantFee).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal transferFee(BigDecimal listingPrice) {
        requireListingPrice(listingPrice);
        return listingPrice.multiply(transferFeeRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal profitableBidLimit(BigDecimal listingPrice) {
        requireListingPrice(listingPrice);
        return listingPrice.subtract(merchantFee(listingPrice))
                .subtract(transferFee(listingPrice))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isProfitable(BigDecimal bidPrice, BigDecimal listingPrice) {
        return bidPrice != null && listingPrice != null
                && listingPrice.compareTo(BigDecimal.ZERO) > 0
                && bidPrice.compareTo(profitableBidLimit(listingPrice)) <= 0;
    }

    public static Boolean parseExcelEnabled(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "是", "true", "1", "yes", "y" -> true;
            case "否", "false", "0", "no", "n" -> false;
            default -> throw new IllegalArgumentException(
                    "费率配置是否启用仅支持是/否、true/false或1/0");
        };
    }

    private static BigDecimal valueOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value != null ? value.stripTrailingZeros() : defaultValue;
    }

    private static void validateRate(BigDecimal rate, String label) {
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(label + "必须在0到1之间");
        }
    }

    private static void requireListingPrice(BigDecimal listingPrice) {
        if (listingPrice == null || listingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("现货标价必须大于0");
        }
    }
}
