package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.model.shoes.ShoesRequest;
import cn.ken.shoes.model.shoes.ShoesVO;
import cn.ken.shoes.service.FileService;
import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.service.PoisonService;
import cn.ken.shoes.service.ShoesService;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("shoes")
public class ShoesController {

    private final String token = "a04b2bd3138555154fe3af1e645a2186";

    @Resource
    private KickScrewClient kickScrewClient;
    @Resource
    private PoisonClient poisonClient;
    @Resource
    private PoisonService poisonService;
    @Resource
    private ItemService kickScrewItemService;
    @Resource
    private ShoesService shoesService;
    @Resource
    private FileService fileService;

    @GetMapping("shoesPage")
    public PageResult<List<ShoesVO>> shoesPage(ShoesRequest request) {
        return shoesService.page(request);
    }

    @GetMapping("queryPriceBySpu")
    public String queryPriceBySpu(String modelNo, Long spuId) {
        return JSON.toJSONString(poisonClient.queryPriceBySpu(modelNo, spuId));
    }

    @GetMapping("queryPriceBySpuV2")
    public String queryPriceBySpuV2(String modelNo, Long spuId) {
        return JSON.toJSONString(poisonClient.queryPriceBySpuV2(modelNo, spuId));
    }

    @GetMapping("clearKcItems")
    public void clearKcItems() {
        kickScrewClient.deleteAllItems();
    }

    @GetMapping("queryTokenBalance")
    public String queryTokenBalance() {
        return poisonClient.queryTokenBalance();
    }

    @GetMapping("queryByModelNos")
    public String queryByModelNos(String modelNos) {
        return JSON.toJSONString(poisonClient.queryItemByModelNos(Arrays.stream(modelNos.split(",")).toList()));
    }

    @GetMapping("prices")
    public Integer prices(Long skuId) {
        return poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.NORMAL);
    }

    @GetMapping("queryByHandle")
    public String queryByHandle(String handle) {
        return JSON.toJSONString(kickScrewClient.queryItemSizePrice(handle));
    }

    @GetMapping("incremental")
    public void incremental() {
        kickScrewItemService.refreshIncrementalItems();
    }

    @GetMapping("file")
    public void file() {
        fileService.updatePoisonPriceByExcel("3.xlsx");
    }

    @GetMapping("kcp2")
    public void kcp2() {
        kickScrewItemService.refreshAllPricesV2();
    }
}
