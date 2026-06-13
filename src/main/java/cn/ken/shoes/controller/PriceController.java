package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.price.PriceVO;
import cn.ken.shoes.service.PriceService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("price")
public class PriceController {

    @Resource
    private PriceService priceService;

    @GetMapping("queryByModelNo")
    public Result<List<PriceVO>> queryByModelNo(String modelNo) {
        if (modelNo == null || modelNo.isBlank()) {
            return Result.buildError("请输入货号");
        }
        return priceService.queryByModelNo(modelNo.strip());
    }
}
