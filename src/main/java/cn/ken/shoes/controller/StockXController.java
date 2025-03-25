package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.service.StockXService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    public Result<List<String>> queryItems(String brand) {
        return Result.buildSuccess(stockXClient.queryHotItemsByBrand(brand));
    }

    @GetMapping("queryPrices")
    public Result<List<StockXPriceDO>> queryPrices(String productId) {
        stockXService.searchPrices(productId);
        return Result.buildSuccess();
    }

    @GetMapping("getSortType")
    public Result<String> getSortType() {
        return Result.buildSuccess(StockXSwitch.SORT_TYPE.getCode());
    }

    @PostMapping("updateSortType")
    public Result<Boolean> updateSortType(String type) {
        StockXSortEnum sortType = StockXSortEnum.from(type);
        if (sortType == null) {
            return Result.buildError("invalid type");
        }
        StockXSwitch.SORT_TYPE = sortType;
        return Result.buildSuccess();
    }

    @GetMapping("queryBrands")
    public Result<List<BrandDO>> queryBrands() {
        return Result.buildSuccess(stockXClient.queryBrands());
    }

    @GetMapping("refreshItems")
    public Result<Boolean> refreshItems() {
        stockXService.refreshItems();
        return Result.buildSuccess();
    }

    @GetMapping("create")
    public Result<Void> create() {
        List<Pair<String, String>> toCreate = new ArrayList<>();
        toCreate.add(Pair.of("8d981372-77be-4d78-8f75-698bab7e4ec2", "99999"));
        toCreate.add(Pair.of("f2222d16-e2ce-4795-8ef9-5853bef6b44c", "99999"));
        toCreate.add(Pair.of("f4bf56f7-5dd5-4d5f-b5de-26b652162265", "99999"));
        toCreate.add(Pair.of("196e05bd-93a0-47dd-9fdf-e80f98271c4e", "99999"));
        stockXClient.createListing(toCreate);
        return Result.buildSuccess();
    }


}
