package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.poinson.ItemPrice;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("shoes")
public class ShoesController {

    private final String token = "902bee8bf35534400496b2519985cdf1";

    @Resource
    private KickScrewClient kickScrewClient;
    @Autowired
    private PoisonClient poisonClient;

    @GetMapping("list")
    public String getShoes() {
        return HttpUtil.doGet( "https://crewsupply-service-gi7me3n4rq-de.a.run.app/latest/products?offset=0&limit=18&filter=brand%3A1");
    }

    @GetMapping("list2")
    public String test2(String spuId) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("spuId", spuId);
        jsonObject.put("token", token);
        return HttpUtil.doPost( "http://47.100.28.62:8000/getpricebyspuidv3/z", jsonObject.toJSONString());
    }

    @GetMapping("list3")
    public String test3() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("token", token);
        return HttpUtil.doPost( "http://47.100.28.62:8000/getFundsbytoken", jsonObject.toJSONString());
    }

    @GetMapping("category")
    public String category() {
        return JSONObject.toJSONString(KickScrewContext.brandSizes);
    }

    @GetMapping("prices")
    public Integer prices(Long skuId) {
//        return kickScrewClient.queryItemSizePrice("anta-kai-1-jelly-112441113-13");
        return poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.FAST);
//        return null;
    }

    @GetMapping("queryByModelNo")
    public PoisonItem queryByModelNo(String modelNo) {
//        return kickScrewClient.queryItemSizePrice("anta-kai-1-jelly-112441113-13");
        return poisonClient.queryItemByModelNumber(modelNo);
//        return null;
    }

    @GetMapping("queryByHandle")
    public String queryByHandle(String handle) {
        return JSON.toJSONString(kickScrewClient.queryItemSizePrice(handle));
    }
}
