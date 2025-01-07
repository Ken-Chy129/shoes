package cn.ken.shoes.controller;

import cn.ken.shoes.service.KickScrewService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("kc")
public class KickScrewController {

    @Resource
    private KickScrewService kickScrewService;

    @GetMapping("refreshItems")
    public void refreshItems() {
        kickScrewService.scratchAndSaveItems();
    }

    @GetMapping("refreshCategories")
    public Integer refreshCategories() {
        return kickScrewService.scratchAndSaveCategories();
    }

    @GetMapping("refreshPrices")
    public void refreshPrices() throws InterruptedException {
        kickScrewService.scratchAndSaveItemPrices();
    }
}
