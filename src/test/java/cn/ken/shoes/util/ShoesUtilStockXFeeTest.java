package cn.ken.shoes.util;

import cn.ken.shoes.common.PriceDownType;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXFeeConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShoesUtilStockXFeeTest {

    @Test
    void usesNewThreeFiveFormulaAndKeepsIntegerTruncation() {
        assertThat(ShoesUtil.getThreeFivePrice(1000)).isEqualTo(902);
        assertThat(ShoesUtil.getThreeFivePrice(999)).isEqualTo(901);
    }

    @Test
    void resolvesSpecialProfilesWithFieldLevelFallbackToDefault() {
        StockXAccount account = new StockXAccount();
        account.setTransferFeeRate(0.03);
        account.setMerchantFeeRate(0.07);
        account.setMinMerchantFee(5.79);
        account.setPlatformShippingFee(4);
        account.setFreight(25);
        account.setMinProfit(-30);

        StockXFeeConfig poison = new StockXFeeConfig();
        poison.setMerchantFeeRate(0.05);
        poison.setFreight(18);
        account.setSpecialStyleFeeConfig(poison);

        StockXFeeConfig resolved = account.resolveFeeConfig(PriceDownType.POISON);
        assertThat(resolved.getTransferFeeRate()).isEqualTo(0.03);
        assertThat(resolved.getMerchantFeeRate()).isEqualTo(0.05);
        assertThat(resolved.getMinMerchantFee()).isEqualTo(5.79);
        assertThat(resolved.getPlatformShippingFee()).isEqualTo(4);
        assertThat(resolved.getFreight()).isEqualTo(18);
        assertThat(resolved.getMinProfit()).isEqualTo(-30);
    }

    @Test
    void calculatesProfitWithTheSelectedFeeProfile() {
        StockXFeeConfig fees = new StockXFeeConfig();
        fees.setTransferFeeRate(0.03);
        fees.setMerchantFeeRate(0.07);
        fees.setMinMerchantFee(5.79);
        fees.setPlatformShippingFee(4.0);
        fees.setFreight(25);
        fees.setMinProfit(-30);

        assertThat(ShoesUtil.getStockxEarn(500, 100, fees))
                .isEqualTo((100 - 3 - 7 - 4) * PriceSwitch.EXCHANGE_RATE - 25 - 500);
    }
}
