package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("poison")
public class PoisonController {

    @Resource
    private PoisonService poisonService;

    @GetMapping("refreshAllPrice")
    public Result<Void> refreshPrice(boolean clearOld) {
        Thread.startVirtualThread(() -> poisonService.refreshPrice(clearOld));
        return Result.buildSuccess();
    }
}
