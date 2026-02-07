package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.service.StockXService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("stockx")
public class StockXController {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXService stockXService;

    @GetMapping("queryItems")
    public Result<List<StockXPriceDO>> queryItems(String brand, Integer pageIndex) {
        return Result.buildSuccess(stockXClient.queryHotItemsByBrandWithPrice(brand, pageIndex));
    }

    @GetMapping("queryItemsV2")
    public Result<List<StockXPriceDO>> queryItemsV2(String brand) {
        return Result.buildSuccess(stockXClient.queryItemWithPrice(brand, 1));
    }

    @GetMapping("searchItems")
    public Result<List<StockXPriceExcel>> searchItems(String query, Integer page, String sortType, String searchType) {
        Pair<Integer, List<StockXPriceExcel>> pair = stockXClient.searchItemWithPrice(query, page, sortType, searchType);
        if (pair == null) {
            return Result.buildError("no result");
        }
        return Result.buildSuccess(pair.getValue());
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
        return Result.buildSuccess(stockXClient.querySellingItems(1, "HM9606-400"));
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

    @GetMapping("queryOrder")
    public Result<JSONObject> queryOrder() {
        return Result.buildSuccess(stockXClient.queryOrders(null));
    }

    @PostMapping("extendAllItems")
    public Result<Void> extendAllItems() {
        Thread.startVirtualThread(() -> stockXService.extendAllItems());
        return Result.buildSuccess();
    }

    @PostMapping("delistAllItems")
    public Result<Void> delistAllItems() {
        Thread.startVirtualThread(() -> stockXService.delistAllItems());
        return Result.buildSuccess();
    }

}
