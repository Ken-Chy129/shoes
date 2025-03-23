package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.PoisonSwitch;
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

    @GetMapping("setQueryPriceSwitch")
    public Result<Void> setQueryPriceSwitch(boolean isStop) {
        PoisonSwitch.STOP_QUERY_PRICE = isStop;
        return Result.buildSuccess();
    }
}
