package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
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
        return Result.buildSuccess(stockXClient.querySellingItems(null));
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

    @GetMapping("testUp")
    public Result<String> testUp() {
        Pair<String, Integer> pair = Pair.of("62a7f698-7896-4fc3-b499-a56da389dd6f", 9999);
        Pair<String, Integer> pair2 = Pair.of("9df4a9c7-cb5a-4dfb-a804-c936b9bf5d27", 9999);
        stockXClient.createListing(List.of(pair, pair2));
        return Result.buildSuccess();
    }

    @GetMapping("testDown")
    public Result<String> testDown(String id) {
        Pair<String, Integer> pair = Pair.of(id, 9999);
        stockXClient.deleteItems(List.of(pair));
        return Result.buildSuccess();
    }

}
