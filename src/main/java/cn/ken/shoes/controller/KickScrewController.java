package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.service.KickScrewService;
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
    public void refreshItems() {
        kickScrewService.refreshItems();
    }

    /**
     * 刷新品牌信息
     */
    @GetMapping("refreshBrands")
    public void refreshBrands() {
        kickScrewService.refreshBrand();
    }

    /**
     * 刷新当前kc商品表所有商品的价格
     */
    @GetMapping("refreshPrices")
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
     * 刷新热门商品
     */
    @GetMapping("refreshHotItems")
    public void refreshHotItems() {
        kickScrewService.refreshHotItems();
    }

    /**
     * 根据货号刷新价格
     */
    @GetMapping("refreshPricesByModelNos")
    public void refreshPricesByModelNos(List<String> modelNos) {
        kickScrewService.refreshPricesByModelNos(modelNos);
    }
}
