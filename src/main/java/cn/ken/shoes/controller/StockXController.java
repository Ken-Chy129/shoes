package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.excel.StockXOrderExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.service.StockXService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    @GetMapping("queryItemsV2")
    public Result<List<StockXPriceDO>> queryItemsV2(String brand) {
        return Result.buildSuccess(stockXClient.queryItemWithPrice(brand, 1));
    }

    @GetMapping("searchItems")
    public Result<List<StockXPriceExcel>> searchItems(String query, Integer page, String sortType) {
        Pair<Integer, List<StockXPriceExcel>> pair = stockXClient.searchItemWithPrice(query, page, sortType);
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

    @GetMapping("queryOrder")
    public Result<JSONObject> queryOrder() {
        return Result.buildSuccess(stockXClient.queryOrders(null));
    }

    @PostMapping("extendAllItems")
    public Result<Void> extendAllItems() {
        Thread.startVirtualThread(() -> stockXService.extendAllItems());
        return Result.buildSuccess();
    }
}
