package cn.ken.shoes.controller;

import cn.ken.shoes.client.StockXClient;
import com.alibaba.fastjson.JSONObject;
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

    @GetMapping("config")
    public JSONObject config() {
        return stockXClient.getToken();
    }

    @GetMapping("authorize")
    public String authorize() {
        return stockXClient.getAuthorizeUrl();
    }


}
