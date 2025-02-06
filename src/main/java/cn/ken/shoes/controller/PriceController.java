package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.model.price.PriceRequest;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PriceService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("price")
public class PriceController {

    @Resource
    private PriceService priceService;

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private KickScrewService kickScrewService;

    @GetMapping("list")
    public Result<List<ItemDO>> list(PriceRequest priceRequest) {
        return priceService.queryPriceByCondition(priceRequest);
    }

    @GetMapping("list2")
    public List<KickScrewItemDO> list2(String brand) {
        KickScrewAlgoliaRequest kickScrewAlgoliaRequest = new KickScrewAlgoliaRequest();
        kickScrewAlgoliaRequest.setBrands(List.of(brand));
        kickScrewAlgoliaRequest.setReleaseYears(List.of(2024));
        kickScrewAlgoliaRequest.setPageIndex(1);
        return kickScrewClient.queryItemPageV2(kickScrewAlgoliaRequest);
    }

    @GetMapping("test")
    public void scratch() {
        priceService.scratchAndSaveItems();
    }

    @GetMapping("queryBrand")
    public KickScrewCategory queryBrand() {
        return kickScrewClient.queryBrand();
    }

    @GetMapping("queryPage")
    public Integer queryPage() {
        KickScrewAlgoliaRequest kickScrewAlgoliaRequest = new KickScrewAlgoliaRequest();
        kickScrewAlgoliaRequest.setBrands(List.of("NIKE"));
        kickScrewAlgoliaRequest.setReleaseYears(List.of(2024));
        return kickScrewClient.countItemPageV2(kickScrewAlgoliaRequest);
    }

    @GetMapping("size")
    public void size() {
        kickScrewService.queryBrandGenderSizeMap();
    }
}
