package cn.ken.shoes.controller;

import cn.ken.shoes.client.StockXClient;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("stockx")
public class StockXController {

    @Resource
    private StockXClient stockXClient;

    @GetMapping("queryItems")
    public String queryItems(String brand) {
        return stockXClient.queryItemByBrand(brand);
    }
}
