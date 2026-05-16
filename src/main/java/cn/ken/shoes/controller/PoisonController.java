package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.annotation.CheckApiToken;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.SpecialPriceMapper;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.SpecialPriceDO;
import cn.ken.shoes.util.SqlHelper;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@RestController
@RequestMapping("poison")
public class PoisonController {

    @Resource
    private SpecialPriceMapper specialPriceMapper;

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PriceManager priceManager;

    @GetMapping("batchQueryPrice")
    public Result<List<PoisonPriceDO>> batchQueryPrice(String query) {
        return Result.buildSuccess(poisonClient.batchQueryPrice(List.of(query)));
    }

    @CheckApiToken
    @GetMapping("price")
    public Result<List<PoisonPriceDO>> queryPrice(@RequestParam String modelNo) {
        List<PoisonPriceDO> prices = priceManager.queryPriceForExternal(modelNo);
        if (prices.isEmpty()) {
            return Result.buildError("未找到该货号的价格信息");
        }
        return Result.buildSuccess(prices);
    }

    @CheckApiToken
    @PostMapping("batchPrice")
    public Result<Map<String, List<PoisonPriceDO>>> batchPrice(@RequestBody List<String> modelNos) {
        Map<String, List<PoisonPriceDO>> result = priceManager.batchQueryPriceForExternal(modelNos);
        return Result.buildSuccess(result);
    }

    @GetMapping("tokenInfo")
    public Result<JSONObject> tokenInfo() {
        JSONObject info = poisonClient.queryTokenInfo();
        return info != null ? Result.buildSuccess(info) : Result.buildError("查询token信息失败");
    }

    @GetMapping("querySpecialPrice")
    public Result<List<String>> querySpecialPrice() {
        List<SpecialPriceDO> specialPriceDOS = specialPriceMapper.selectList(null);
        List<String> result = specialPriceDOS.stream().map(price -> STR."\{price.getModelNo()}:\{price.getEuSize()}:\{price.getPrice()}").toList();
        return Result.buildSuccess(result);
    }

    @PostMapping("updateSpecialPrice")
    public Result<Boolean> updateSpecialPrice(@RequestBody JSONObject jsonObject) {
        String modelNos = Optional.ofNullable(jsonObject.getString("modelNos")).orElse("");
        List<String> modelNoList = Arrays.stream(modelNos.split(",")).filter(StrUtil::isNotBlank).map(String::trim).toList();
        List<SpecialPriceDO> toInsert = new ArrayList<>();
        for (String model : modelNoList) {
            SpecialPriceDO specialPriceDO = new SpecialPriceDO();
            String[] split = model.split(":");
            if (split.length != 3) {
                continue;
            }
            specialPriceDO.setModelNo(split[0]);
            specialPriceDO.setEuSize(split[1]);
            specialPriceDO.setPrice(Integer.parseInt(split[2]));
            toInsert.add(specialPriceDO);
        }
        ShoesContext.clearSpecialPrice();
        specialPriceMapper.delete(null);
        if (toInsert.isEmpty()) {
            return Result.buildSuccess(false);
        }
        toInsert.forEach(ShoesContext::addSpecialPrice);
        SqlHelper.batch(toInsert, specialPriceDO -> specialPriceMapper.insert(specialPriceDO));
        return Result.buildSuccess(true);
    }

}
