package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.*;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.model.brand.BrandRequest;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.setting.PriceSetting;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.SqlHelper;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("setting")
public class SettingController {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private StockXClient stockXClient;

    @Resource
    private CustomModelMapper customModelMapper;

    @GetMapping("poison")
    public Result<JSONObject> queryPoisonSetting() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("apiMode", PoisonSwitch.API_MODE);
        jsonObject.put("maxPrice", PoisonSwitch.MAX_PRICE);
        jsonObject.put("openImportDBData", PoisonSwitch.OPEN_IMPORT_DB_DATA);
        jsonObject.put("openNoPriceCache", PoisonSwitch.OPEN_NO_PRICE_CACHE);
        jsonObject.put("stopQueryPrice", PoisonSwitch.STOP_QUERY_PRICE);
        return Result.buildSuccess(jsonObject);
    }

    @PostMapping("poison")
    public Result<JSONObject> updatePoisonSetting(@RequestBody JSONObject jsonObject) {
        PoisonSwitch.API_MODE = jsonObject.getInteger("apiMode");
        PoisonSwitch.MAX_PRICE = jsonObject.getInteger("maxPrice");
        PoisonSwitch.OPEN_IMPORT_DB_DATA = jsonObject.getBoolean("openImportDBData");
        PoisonSwitch.OPEN_NO_PRICE_CACHE = jsonObject.getBoolean("openNoPriceCache");
        PoisonSwitch.STOP_QUERY_PRICE = jsonObject.getBoolean("stopQueryPrice");
        return Result.buildSuccess(jsonObject);
    }

    @GetMapping("kc")
    public Result<PriceSetting> queryKcSetting() {
        PriceSetting priceSetting = new PriceSetting();
        priceSetting.setExchangeRate(PriceSwitch.EXCHANGE_RATE);
        priceSetting.setFreight(PriceSwitch.FREIGHT);
        priceSetting.setMinProfit(PriceSwitch.MIN_PROFIT);
        priceSetting.setKcGetRate(PriceSwitch.KC_GET_RATE);
        priceSetting.setKcServiceFee(PriceSwitch.KC_SERVICE_FEE);
        return Result.buildSuccess(priceSetting);
    }

    @PostMapping("kc")
    public Result<Boolean> updateKcSetting(@RequestBody PriceSetting priceSetting) {
        PriceSwitch.EXCHANGE_RATE = priceSetting.getExchangeRate();
        PriceSwitch.FREIGHT = priceSetting.getFreight();
        PriceSwitch.MIN_PROFIT = priceSetting.getMinProfit();
        PriceSwitch.KC_GET_RATE = priceSetting.getKcGetRate();
        PriceSwitch.KC_SERVICE_FEE = priceSetting.getKcServiceFee();
        return Result.buildSuccess(true);
    }

    @GetMapping("stockx")
    public Result<JSONObject> queryStockxSetting() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("sortType", StockXSwitch.SORT_TYPE.getCode());
        jsonObject.put("priceType", StockXSwitch.PRICE_TYPE.getCode());
        return Result.buildSuccess(jsonObject);
    }

    @PostMapping("stockx")
    public Result<Boolean> updateStockxSetting(@RequestBody JSONObject jsonObject) {
        StockXSortEnum sortType = StockXSortEnum.from(jsonObject.getString("sortType"));
        StockXPriceEnum priceType = StockXPriceEnum.from(jsonObject.getString("priceType"));
        if (Objects.isNull(sortType) || Objects.isNull(priceType)) {
            return Result.buildError("非法类型");
        }
        StockXSwitch.SORT_TYPE = sortType;
        StockXSwitch.PRICE_TYPE = priceType;
        return Result.buildSuccess(true);
    }

    @GetMapping("queryStockxConfig")
    public Result<StockXConfig.OAuth2Config> queryStockxConfig() {
        return Result.buildSuccess(StockXConfig.CONFIG);
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
            return Result.buildError("初始化令牌失败");
        }
    }

    @PostMapping("stockx/refreshToken")
    public Result<Boolean> refreshToken() {
        if (stockXClient.refreshToken()) {
            return Result.buildSuccess(true);
        } else {
            return Result.buildError("刷新令牌失败");
        }
    }

    @GetMapping("stockx/getAuthorization")
    public Result<String> getAuthorization() {
        return Result.buildSuccess(StockXConfig.CONFIG.getAccessToken());
    }

    @PostMapping("stockx/updateAuthorization")
    public Result<Boolean> updateAuthorization(@RequestBody JSONObject jsonObject) {
        StockXConfig.CONFIG.setAccessToken(jsonObject.getString("accessToken"));
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
        String modelNos = Optional.ofNullable(jsonObject.getString("modelNos")).orElse("");
        List<String> modelNoList = Arrays.stream(modelNos.split(",")).filter(StrUtil::isNotBlank).map(String::trim).toList();
        kickScrewService.updateMustCrawlModelNos(modelNoList);
        return Result.buildSuccess(true);
    }

    @GetMapping("queryForbiddenCrawlModelNos")
    public Result<List<String>> queryForbiddenCrawlModelNos() {
        List<CustomModelDO> customModelDOS = customModelMapper.selectByType(CustomPriceTypeEnum.FLAWS.getCode());
        List<String> result = customModelDOS.stream().map(customModelDO -> {
            String modelNo = customModelDO.getModelNo();
            String euSize = customModelDO.getEuSize();
            if (StrUtil.isBlank(euSize)) {
                return modelNo;
            }
            return modelNo + ":" + euSize;
        }).toList();
        return Result.buildSuccess(result);
    }

    @PostMapping("updateForbiddenCrawlModelNos")
    public Result<Boolean> updateForbiddenCrawlModelNos(@RequestBody JSONObject jsonObject) {
        String modelNos = Optional.ofNullable(jsonObject.getString("modelNos")).orElse("");
        List<String> modelList = Arrays.stream(modelNos.split(",")).filter(StrUtil::isNotBlank).map(String::trim).toList();
        List<CustomModelDO> toInsert = modelList.stream().filter(StrUtil::isNotBlank).map(model -> {
            CustomModelDO customModelDO = new CustomModelDO();
            customModelDO.setType(CustomPriceTypeEnum.FLAWS.getCode());
            String[] split = model.split(":");
            if (split.length != 2) {
                customModelDO.setModelNo(model);
            } else {
                customModelDO.setModelNo(split[0]);
                customModelDO.setEuSize(split[1]);
            }
            return customModelDO;
        }).toList();
        ShoesContext.clearFlawsModelSet();
        customModelMapper.clearByType(CustomPriceTypeEnum.FLAWS.getCode());
        if (CollectionUtils.isEmpty(toInsert)) {
            return Result.buildSuccess(true);
        }
        toInsert.forEach(ShoesContext::addFlawsModel);
        SqlHelper.batch(toInsert, modelDO -> customModelMapper.insertIgnore(modelDO));
        return Result.buildSuccess(true);
    }

    @PostMapping("updateDefaultCrawlCnt")
    public Result<Boolean> updateDefaultCrawlCnt(@RequestBody JSONObject jsonObject) {
        Integer defaultCnt = jsonObject.getInteger("defaultCnt");
        String platform = Optional.ofNullable(jsonObject.getString("platform")).orElse("kc");
        if (defaultCnt == null) {
            return Result.buildSuccess(Boolean.FALSE);
        }
        brandMapper.updateDefaultCrawlCnt(defaultCnt, platform);
        return Result.buildSuccess(true);
    }
}
