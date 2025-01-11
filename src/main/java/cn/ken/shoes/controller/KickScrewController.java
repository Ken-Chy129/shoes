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

    @GetMapping("refreshItems")
    public void refreshItems() {
        kickScrewService.scratchAndSaveItems();
    }

    @GetMapping("refreshCategories")
    public Integer refreshCategories() {
        return kickScrewService.scratchAndSaveBrand();
    }

    @GetMapping("refreshPrices")
    public void refreshPrices() throws InterruptedException {
        kickScrewService.scratchAndSaveItemPrices();
    }

    @GetMapping("querySizeChart")
    public List<Map<String, String>> querySizeChart(String brand, String modelNo) {
        return kickScrewClient.queryItemSizeChart(brand, modelNo);
    }
}
