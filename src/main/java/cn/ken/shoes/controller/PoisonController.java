package cn.ken.shoes.controller;

import cn.ken.shoes.annotation.CheckApiToken;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("poison")
public class PoisonController {

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


}
