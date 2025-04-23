package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.service.StockXService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("stockx")
public class StockXController {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXService stockXService;

    @GetMapping("queryItems")
    public Result<List<StockXPriceDO>> queryItems(String brand) {
        return Result.buildSuccess(stockXClient.queryHotItemsByBrandWithPrice(brand, 1));
    }

    @GetMapping("queryPrices")
    public Result<List<StockXPriceDO>> queryPrices(String productId) {
        return Result.buildSuccess(stockXClient.queryPrice(productId));
    }

    @GetMapping("queryBrands")
    public Result<List<BrandDO>> queryBrands() {
        return Result.buildSuccess(stockXClient.queryBrands());
    }

    @GetMapping("querySellingItems")
    public Result<JSONObject> querySellingItems() {
        return Result.buildSuccess(stockXClient.querySellingItems(null, "HM9606-400"));
    }

    @GetMapping("refreshBrand")
    public Result<Boolean> refreshBrand() {
        stockXService.refreshBrand();
        return Result.buildSuccess();
    }

    @GetMapping("refreshPrices")
    public Result<Boolean> refreshPrices() {
        stockXService.refreshPrices();
        return Result.buildSuccess();
    }

    @GetMapping("queryToDealItems")
    public Result<JSONObject> queryToDealItems() {
        return Result.buildSuccess(stockXClient.queryToDeal(null));
    }

    @GetMapping("extendAllItems")
    public Result<Void> extendAllItems() {
        stockXService.extendAllItems();
        return Result.buildSuccess();
    }
}
