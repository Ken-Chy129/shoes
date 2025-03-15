package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.mapper.StockXItemMapper;
import cn.ken.shoes.model.entity.StockXItemDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockXService {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXItemMapper stockXItemMapper;

    public void searchItems() {
        for (int i = 1; i < 600; i++) {
            List<StockXItemDO> nike = stockXClient.searchItems("nike", i);
            stockXItemMapper.insert(nike);
        }
    }
}
