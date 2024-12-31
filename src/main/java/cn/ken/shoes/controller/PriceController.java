package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewItem;
import cn.ken.shoes.model.price.PriceRequest;
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

    @GetMapping("list")
    public Result<List<ItemDO>> list(PriceRequest priceRequest) {
        return priceService.queryPriceByCondition(priceRequest);
    }

    @GetMapping("list2")
    public List<KickScrewItem> list2(String brand) {
        return kickScrewClient.queryItemByBrand(brand, 1);
    }
}
