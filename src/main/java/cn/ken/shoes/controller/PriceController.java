package cn.ken.shoes.controller;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.model.price.PriceVO;
import cn.ken.shoes.service.PriceService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("price")
public class PriceController {

    @Resource
    private PriceService priceService;

    @Resource
    private PriceManager priceManager;

    @GetMapping("queryByModelNo")
    public Result<List<PriceVO>> queryByModelNo(String modelNo) {
        if (modelNo == null || modelNo.isBlank()) {
            return Result.buildError("请输入货号");
        }
        return priceService.queryByModelNo(modelNo.strip());
    }

    @PostMapping("invalidateCache")
    public Result<String> invalidateCache(@RequestParam(defaultValue = "false") boolean clearDb) {
        long count = priceManager.invalidateAll(clearDb);
        String msg = "已清除" + count + "条内存缓存";
        if (clearDb) {
            msg += "，数据库已清空";
        }
        return Result.buildSuccess(msg);
    }

    @PostMapping("clearNoPriceCache")
    public Result<String> clearNoPriceCache() {
        int count = ShoesContext.getNoPriceModelSet().size();
        ShoesContext.clearNoPriceModelSet();
        return Result.buildSuccess("已清除" + count + "个无价缓存货号");
    }
}
