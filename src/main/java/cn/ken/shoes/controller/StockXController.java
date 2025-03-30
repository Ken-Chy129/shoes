package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.service.StockXService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("stockx")
public class StockXController {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXService stockXService;

    @GetMapping("queryItems")
    public Result<List<StockXItemDO>> queryItems(String brand) {
        return Result.buildSuccess(stockXClient.queryHotItemsByBrand(brand, 1));
    }

    @GetMapping("queryPrices")
    public Result<List<StockXPriceDO>> queryPrices(String productId) {
        return Result.buildSuccess(stockXClient.queryPrice(productId));
    }

    @GetMapping("queryBrands")
    public Result<List<BrandDO>> queryBrands() {
        return Result.buildSuccess(stockXClient.queryBrands());
    }

    @GetMapping("refreshBrand")
    public Result<Boolean> refreshBrand() {
        stockXService.refreshBrand();
        return Result.buildSuccess();
    }

    @GetMapping("refreshItems")
    public Result<Boolean> refreshItems() {
        stockXService.refreshItems();
        return Result.buildSuccess();
    }

}
