package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.model.brand.BrandRequest;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.setting.PriceSetting;
import cn.ken.shoes.service.KickScrewService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("setting")
public class SettingController {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private StockXClient stockXClient;

    @GetMapping("poison")
    public Result<JSONObject> queryPoisonSetting() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("apiMode", PoisonSwitch.API_MODE);
        jsonObject.put("maxPrice", PoisonSwitch.MAX_PRICE);
        return Result.buildSuccess(jsonObject);
    }

    @PostMapping("poison")
    public Result<JSONObject> updatePoisonSetting(@RequestBody JSONObject jsonObject) {
        PoisonSwitch.API_MODE = jsonObject.getInteger("apiMode");
        PoisonSwitch.MAX_PRICE = jsonObject.getInteger("maxPrice");
        return Result.buildSuccess(jsonObject);
    }

    @GetMapping("kc")
    public Result<PriceSetting> queryKcSetting() {
        PriceSetting priceSetting = new PriceSetting();
        priceSetting.setExchangeRate(PriceSwitch.EXCHANGE_RATE);
        priceSetting.setFreight(PriceSwitch.FREIGHT);
        priceSetting.setMinProfit(PriceSwitch.MIN_PROFIT);
        return Result.buildSuccess(priceSetting);
    }

    @PostMapping("kc")
    public Result<Boolean> updateKcSetting(@RequestBody PriceSetting priceSetting) {
        PriceSwitch.EXCHANGE_RATE = priceSetting.getExchangeRate();
        PriceSwitch.FREIGHT = priceSetting.getFreight();
        PriceSwitch.MIN_PROFIT = priceSetting.getMinProfit();
        return Result.buildSuccess(true);
    }

    @GetMapping("stockx/getAuthorizeUrl")
    public Result<String> getAuthorizeUrl() {
        return Result.buildSuccess(stockXClient.getAuthorizeUrl());
    }

    @PostMapping("stockx/initToken")
    public Result<Boolean> initToken() {
        if (stockXClient.initToken()) {
            return Result.buildSuccess(true);
        } else {
            return Result.buildError("初始化失败");
        }
    }

    @GetMapping("stockx")
    public Result<StockXConfig.OAuth2Config> queryStockxSetting() {
        return Result.buildSuccess(StockXConfig.CONFIG);
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

    @PostMapping("updateBrandSetting")
    public Result<Boolean> updateBrandSetting(@RequestBody BrandDO brandDO) {
        brandMapper.updateByName(brandDO);
        return Result.buildSuccess(Boolean.TRUE);
    }

    @GetMapping("queryMustCrawlModelNos")
    public Result<List<String>> queryMustCrawlModelNos() {
        return Result.buildSuccess(kickScrewService.queryMustCrawlModelNos());
    }

    @PostMapping("updateMustCrawlModelNos")
    public Result<Boolean> updateMustCrawlModelNos(@RequestBody JSONObject jsonObject) {
        String modelNos = jsonObject.getString("modelNos");
        if (StrUtil.isBlank(modelNos)) {
            return Result.buildSuccess(Boolean.TRUE);
        }
        List<String> modelNoList = Arrays.stream(modelNos.split(",")).map(String::trim).toList();
        kickScrewService.updateMustCrawlModelNos(modelNoList);
        return Result.buildSuccess(true);
    }

    @PostMapping("updateDefaultCrawlCnt")
    public Result<Boolean> updateDefaultCrawlCnt(@RequestBody JSONObject jsonObject) {
        Integer defaultCnt = jsonObject.getInteger("defaultCnt");
        if (defaultCnt == null) {
            return Result.buildSuccess(Boolean.FALSE);
        }
        kickScrewService.updateDefaultCrawlCnt(defaultCnt);
        return Result.buildSuccess(true);
    }
}
