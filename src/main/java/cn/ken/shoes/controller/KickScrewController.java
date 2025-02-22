package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.model.entity.KickScrewPriceDO;
import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PriceService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("kc")
public class KickScrewController {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private PriceService priceService;

    @Resource
    private ItemService kickScrewItemService;

    @GetMapping("refreshItems")
    public void refreshItems() {
        kickScrewService.scratchAndSaveItems();
    }

    @GetMapping("refreshCategories")
    public Integer refreshCategories() {
        return kickScrewService.scratchAndSaveBrand();
    }

    @GetMapping("refreshPrices")
    public void refreshPrices() throws InterruptedException {
        kickScrewService.scratchAndSaveItemPrices();
    }

    @GetMapping("querySizeChart")
    public List<Map<String, String>> querySizeChart(String brand, String modelNo) {
        return kickScrewClient.queryItemSizeChart(brand, modelNo);
    }

    @GetMapping("item")
    public void item() {
        kickScrewItemService.refreshAllItems();
    }

    @GetMapping("prices")
    public void prices() {
        kickScrewItemService.refreshAllPrices();
    }

    @GetMapping("poisonPrice")
    public void poisonPrice() {
        priceService.refreshPoisonPrices();
    }

    //todo:1.每天定时更新一次品牌，2.每天定时替换热门商品50页*24=1200，每次分页大小100（区分手动导入和自动获取，手动导入的不会清楚，除非手动清除） 3.支持查询和配置当前每个商家爬取的货号量 4.支持手动输入货号增量导入商品 5.

    @GetMapping("queryLowestPrices")
    public List<KickScrewPriceDO> queryLowestPrices() {
        long l = System.currentTimeMillis();
        List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(List.of("GZ6322", "1002072-CHE", "VN0A4BV96Z6", "3236-CLMN", "ABTU003-4", "3024114-106", "172585C", "A02410C", "VN000W4NDI0", "167809C", "172586C"));
        System.out.println(System.currentTimeMillis() - l);
        return kickScrewPriceDOS;
    }
}
