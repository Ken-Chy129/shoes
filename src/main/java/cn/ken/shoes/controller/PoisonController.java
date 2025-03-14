package cn.ken.shoes.controller;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("poison")
public class PoisonController {

    @Resource
    private PoisonService poisonService;

    @Resource
    private PoisonClient poisonClient;

    @GetMapping("refreshPrice")
    public Result<Void> refreshPrice(boolean overwriteOld) {
        Thread.startVirtualThread(() -> poisonService.refreshPrice(overwriteOld));
        return Result.buildSuccess();
    }

    @GetMapping("testPriceV3")
    public Result<List<PoisonPriceDO>> testPriceV3(Long spuId) {
        return Result.buildSuccess(poisonClient.queryPriceV3(spuId));
    }
}
