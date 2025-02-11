package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.model.setting.PriceSetting;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("setting")
public class SettingController {

    @GetMapping("queryPriceSetting")
    public Result<PriceSetting> queryPriceSetting() {
        PriceSetting priceSetting = new PriceSetting();
        priceSetting.setExchangeRate(PriceSwitch.EXCHANGE_RATE);
        priceSetting.setFreight(PriceSwitch.FREIGHT);
        priceSetting.setPlatformRate(PriceSwitch.PLATFORM_RATE);
        priceSetting.setMinProfitRate(PriceSwitch.MIN_PROFIT_RATE);
        priceSetting.setMinProfit(PriceSwitch.MIN_PROFIT);
        return Result.buildSuccess(priceSetting);
    }

    @PostMapping("price")
    public Result<Boolean> updatePriceSetting(PriceSetting priceSetting) {
        PriceSwitch.EXCHANGE_RATE = priceSetting.getExchangeRate();
        PriceSwitch.FREIGHT = priceSetting.getFreight();
        PriceSwitch.PLATFORM_RATE = priceSetting.getPlatformRate();
        PriceSwitch.MIN_PROFIT_RATE = priceSetting.getMinProfitRate();
        PriceSwitch.MIN_PROFIT = priceSetting.getMinProfit();
        return Result.buildSuccess(true);
    }

}
