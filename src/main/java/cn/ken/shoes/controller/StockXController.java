package cn.ken.shoes.controller;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.stockx.StockXItem;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("stockx")
public class StockXController {

    @Resource
    private StockXClient stockXClient;

    @GetMapping("queryItems")
    public String queryItems(String brand) {
        return stockXClient.queryItemByBrand(brand);
    }

    @GetMapping("test")
    public Result<List<StockXItem>> test(String query) {
        return Result.buildSuccess(stockXClient.searchItems(query, 1));
    }

}
