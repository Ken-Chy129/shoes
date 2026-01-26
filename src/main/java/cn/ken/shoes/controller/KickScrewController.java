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
        return Result.buildSuccess(kickScrewClient.queryStockList(0, 10));
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
}
