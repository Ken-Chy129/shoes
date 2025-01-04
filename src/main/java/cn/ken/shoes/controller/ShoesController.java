package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("shoes")
public class ShoesController {

    private final String token = "a04b2bd3138555154fe3af1e645a2186";

    @Resource
    private KickScrewClient kickScrewClient;
    @Resource
    private PoisonClient poisonClient;

    @GetMapping("list")
    public String getShoes() {
        return HttpUtil.doGet( "https://crewsupply-service-gi7me3n4rq-de.a.run.app/latest/products?offset=0&limit=18&filter=brand%3A1");
    }

    @GetMapping("queryPriceBySpu")
    public String queryPriceBySpu(String spuId) {
        return JSON.toJSONString(poisonClient.queryPriceBySpu(spuId));
    }

    @GetMapping("queryTokenBalance")
    public String queryTokenBalance() {
        return poisonClient.queryTokenBalance();
    }

    @GetMapping("page")
    public Integer page(String brand) {
        return kickScrewClient.queryBrandItemPage(brand);
    }

    @GetMapping("prices")
    public Integer prices(Long skuId) {
        return poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.FAST);
    }

    @GetMapping("queryByModelNo")
    public PoisonItem queryByModelNo(String modelNo) {
        return poisonClient.queryItemByModelNumber(modelNo);
    }

    @GetMapping("queryByHandle")
    public String queryByHandle(String handle) {
        return JSON.toJSONString(kickScrewClient.queryItemSizePrice(handle));
    }
}
