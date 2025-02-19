package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.model.brand.BrandRequest;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.setting.PriceSetting;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("setting")
public class SettingController {

    @Resource
    private BrandMapper brandMapper;

    @GetMapping("queryPriceSetting")
    public Result<PriceSetting> queryPriceSetting() {
        PriceSetting priceSetting = new PriceSetting();
        priceSetting.setExchangeRate(PriceSwitch.EXCHANGE_RATE);
        priceSetting.setFreight(PriceSwitch.FREIGHT);
        priceSetting.setMinProfit(PriceSwitch.MIN_PROFIT);
        return Result.buildSuccess(priceSetting);
    }

    @PostMapping("updatePriceSetting")
    public Result<Boolean> updatePriceSetting(PriceSetting priceSetting) {
        PriceSwitch.EXCHANGE_RATE = priceSetting.getExchangeRate();
        PriceSwitch.FREIGHT = priceSetting.getFreight();
        PriceSwitch.MIN_PROFIT = priceSetting.getMinProfit();
        return Result.buildSuccess(true);
    }

    @GetMapping("queryBrandSetting")
    public PageResult<List<BrandDO>> queryBrandSetting(BrandRequest request) {
        Long count = brandMapper.count(request);
        if (count <= 0) {
            return PageResult.buildSuccess();
        }
        List<BrandDO> brandDOList = brandMapper.selectPage(request);
        PageResult<List<BrandDO>> result = PageResult.buildSuccess(brandDOList);
        result.setTotal(count);
        return result;
    }
}
