package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.StockXItemMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.util.SqlHelper;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StockXService {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXItemMapper stockXItemMapper;

    @Resource
    private BrandMapper brandMapper;

    public void refreshBrand() {
        List<BrandDO> brandDOList = stockXClient.queryBrands();
        brandMapper.batchInsertOrUpdate(brandDOList);
    }

    public void refreshItems() {

    }

    public void searchItems() {
        for (int i = 1; i < 600; i++) {
            List<StockXItemDO> toInsert = stockXClient.searchItems("nike", i);
            SqlHelper.batch(toInsert, item -> stockXItemMapper.insertIgnore(item));
        }
    }

    public void searchPrices(String productId) {
        long startTime = System.currentTimeMillis();
        RateLimiter limiter = RateLimiter.create(3);
        for (int i = 0; i <= 120000; i++) {
            int finalI = i;
            Thread.startVirtualThread(() -> {
                limiter.acquire();
                stockXClient.searchPrice(productId);
                if (finalI % 1000 == 0) {
                    long endTime = System.currentTimeMillis();
                    log.info("i:{}, cost:{}", finalI, endTime - startTime);
                }
            });

        }
        log.info("finish, cost:{}", System.currentTimeMillis() - startTime);
    }
}
