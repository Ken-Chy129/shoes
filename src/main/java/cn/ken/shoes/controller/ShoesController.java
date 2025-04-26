package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.model.shoes.ShoesRequest;
import cn.ken.shoes.model.shoes.ShoesVO;
import cn.ken.shoes.service.FileService;
import cn.ken.shoes.service.ItemService;
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
    private ItemService kickScrewItemService;
    @Resource
    private ShoesService shoesService;
    @Resource
    private FileService fileService;
    @Resource
    private PriceManager priceManager;

    @GetMapping("shoesPage")
    public PageResult<List<ShoesVO>> shoesPage(ShoesRequest request) {
        return shoesService.page(request);
    }

    @GetMapping("queryPriceBySpuV2")
    public String queryPriceBySpuV2(String modelNo, Long spuId) {
        return JSON.toJSONString(poisonClient.queryPriceBySpuV2(modelNo, spuId));
    }

    @GetMapping("queryPriceByModelNo")
    public String queryPriceByModelNo(String modelNo) {
        return JSON.toJSONString(poisonClient.queryPriceByModelNo(modelNo));
    }

    @GetMapping("queryPriceByCache")
    public Integer queryPriceByCache(String modelNo) {
        return priceManager.getPoisonPrice(modelNo, "38.5");
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

    @GetMapping("dump")
    public void dump() {
        priceManager.dumpPrice();
    }

    @GetMapping("importFlawsModel")
    public void importFlawsModel() {
        fileService.importFlawsModel("1.txt");
    }
}
