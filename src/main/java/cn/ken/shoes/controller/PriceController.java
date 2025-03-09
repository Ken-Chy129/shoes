package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.model.price.PriceRequest;
import cn.ken.shoes.model.price.PriceVO;
import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
import cn.ken.shoes.service.PriceService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @Resource
    private PoisonService poisonService;

    @Resource
    private ItemService KickScrewItemServiceImpl;

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

    @GetMapping("queryBrand")
    public KickScrewCategory queryBrand() {
        return kickScrewClient.queryBrand();
    }

    @GetMapping("queryPage")
    public Integer queryPage() {
        KickScrewAlgoliaRequest kickScrewAlgoliaRequest = new KickScrewAlgoliaRequest();
        kickScrewAlgoliaRequest.setBrands(List.of("Nike"));
        kickScrewAlgoliaRequest.setReleaseYears(List.of(2024));
        kickScrewAlgoliaRequest.setStartPrice("30");
        kickScrewAlgoliaRequest.setEndPrice("100");
        return kickScrewClient.countItemPageV2(kickScrewAlgoliaRequest);
    }

    @GetMapping("size")
    public void size() {
        kickScrewService.queryBrandGenderSizeMap();
    }

//    @GetMapping("compareKc")
//    public void compareKc() {
//        KickScrewItemServiceImpl.compareWithPoisonAndChangePrice();
//    }

    @GetMapping("refreshKcPrices")
    public void refreshKcPrices() {
        priceService.refreshKcPrices();
    }

    @GetMapping("queryByModelNo")
    public Result<List<PriceVO>> queryByModelNo(String modelNo, String mode) {
        if (modelNo == null || mode == null) {
            return Result.buildError("参数异常");
        }
        modelNo = modelNo.strip();
        return switch (mode) {
            case "db" -> priceService.queryByModelNoFromDB(modelNo);
            case "realTime" -> priceService.queryByModelNoRealTime(modelNo);
            default -> priceService.queryByModelNoDbFirst(modelNo);
        };
    }
}
