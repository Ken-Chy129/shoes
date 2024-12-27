package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.poinson.Item;
import cn.ken.shoes.service.PoisonService;
import com.alibaba.fastjson.JSONObject;
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

    @GetMapping("test")
    public Result<List<Item>> test(String modelNumber) {
        return poisonService.queryItemByModelNumber(modelNumber);
    }
}
