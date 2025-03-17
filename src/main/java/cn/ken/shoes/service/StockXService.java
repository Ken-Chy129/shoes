package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.mapper.StockXItemMapper;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.util.SqlHelper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockXService {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXItemMapper stockXItemMapper;

    public void searchItems() {
        for (int i = 1; i < 600; i++) {
            List<StockXItemDO> toInsert = stockXClient.searchItems("nike", i);
            SqlHelper.batch(toInsert, item -> stockXItemMapper.insertIgnore(item));
        }
    }

    public List<StockXPriceDO> searchPrices(List<String> productIds) {
        List<StockXPriceDO> result = new ArrayList<>();
        for (String productId : productIds) {
            result.add(stockXClient.searchPrice(productId);
        }
        return result;
    }
}
