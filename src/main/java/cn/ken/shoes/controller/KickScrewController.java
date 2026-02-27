package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.KickScrewPriceDO;
import cn.ken.shoes.service.KickScrewService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("kc")
public class KickScrewController {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private KickScrewClient kickScrewClient;


    /**
     * 刷新商品，重新爬取品牌和热门商品
     */
    @GetMapping("refreshItems")
    public Result<Void> refreshItems() {
        Thread.startVirtualThread(() -> {
            kickScrewService.refreshItems(false);
        });
        return Result.buildSuccess();
    }

    /**
     * 刷新品牌信息
     */
    @GetMapping("refreshBrands")
    public void refreshBrands() {
        Thread.startVirtualThread(() -> kickScrewService.refreshBrand());
    }

    /**
     * 查询尺码表
     */
    @GetMapping("querySizeChart")
    public List<Map<String, String>> querySizeChart(String brand, String modelNo) {
        return kickScrewClient.queryItemSizeChart(brand, modelNo);
    }

    /**
     * 根据货号刷新价格
     */
    @GetMapping("refreshPricesByModelNos")
    public void refreshPricesByModelNos(List<String> modelNos) {
        kickScrewService.refreshPricesByModelNos(modelNos);
    }

    @GetMapping("stock")
    public Result<List<KickScrewPriceDO>> queryStockList() {
        return Result.buildSuccess(kickScrewClient.queryStockList(0, 10000));
    }

    @GetMapping("deleteList")
    public Result<Void> deleteList() {
        kickScrewService.clearNoBenefitItem();
        return Result.buildSuccess();
    }

    @GetMapping("queryHotModels")
    public Result<Boolean> queryHotModels() {
        kickScrewService.queryHotModels();
        return Result.buildSuccess(true);
    }

    @GetMapping("queryPrice")
    public Result<List<KickScrewPriceDO>> queryPrice(String modelNo) {
        List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(List.of(modelNo));
        return Result.buildSuccess(kickScrewPriceDOS);
    }

    /**
     * 自动压价：将所有不是最低价的商品价格设置为最低价-1
     */
    @GetMapping("autoMatch")
    public Result<String> autoMatch() {
        String result = kickScrewClient.autoMatch();
        return Result.buildSuccess(result);
    }

    /**
     * 查询当前上架商品列表
     */
    @GetMapping("listings")
    public Result<List<KickScrewPriceDO>> queryListings(Integer offset, Integer limit) {
        if (offset == null) {
            offset = 0;
        }
        if (limit == null) {
            limit = 100;
        }
        List<KickScrewPriceDO> listings = kickScrewClient.queryListings(offset, limit);
        return Result.buildSuccess(listings);
    }

    /**
     * 查询上架商品总数
     */
    @GetMapping("listings/total")
    public Result<Integer> queryListingsTotal() {
        int total = kickScrewClient.queryListingsTotal();
        return Result.buildSuccess(total);
    }
}
