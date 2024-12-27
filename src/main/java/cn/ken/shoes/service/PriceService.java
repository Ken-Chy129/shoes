package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.model.price.PriceRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PriceService {

    @Resource
    private PoisonClient poisonClient;

    public Result<List<PoisonItem>> queryPriceByCondition(PriceRequest priceRequest) {
        return null;
    }
}
