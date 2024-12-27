package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.Item;
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

    @GetMapping("list")
    public Result<List<Item>> list(PriceRequest priceRequest) {
        return priceService.queryPriceByCondition(priceRequest);
    }
}
