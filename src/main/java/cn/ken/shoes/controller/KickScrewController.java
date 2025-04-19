package cn.ken.shoes.controller;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.KickScrewPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
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
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.REFRESH_INCREMENTAL_ITEMS, operateStatus = TaskDO.OperateStatusEnum.MANUALLY)
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
     * 刷新当前kc商品表所有商品的价格
     */
    @GetMapping("refreshPrices")
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.MANUALLY)
    public void refreshPrices() {
        kickScrewService.refreshPrices();
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
}
