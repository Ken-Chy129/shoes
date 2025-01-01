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

    @GetMapping("refresh")
    public void refreshItems() {
        kickScrewService.scratchAndSaveItems();
    }

    @GetMapping("refreshCategory")
    public void refreshCategory() {
        kickScrewService.scratchAndSaveCategories();
    }
}
