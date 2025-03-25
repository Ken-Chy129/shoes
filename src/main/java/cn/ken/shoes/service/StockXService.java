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
        List<BrandDO> brandDOList = brandMapper.selectByPlatform("stockx");
        for (BrandDO brandDO : brandDOList) {
            if (!brandDO.getNeedCrawl()) {
                continue;
            }
            String brand = brandDO.getName();
            Integer crawlCnt = brandDO.getCrawlCnt();
            int crawlPage = (int) Math.ceil(crawlCnt / 40.0);
            for (int i = 1; i <= crawlPage; i++) {
                List<String> productId = stockXClient.queryHotItemsByBrand(brand, i);
            }
        }
    }

    public void searchItems() {
        for (int i = 1; i < 600; i++) {
            List<StockXItemDO> toInsert = stockXClient.searchItems("nike", i);
            SqlHelper.batch(toInsert, item -> stockXItemMapper.insertIgnore(item));
        }
    }

    public List<StockXPriceDO> searchPrices(String productId) {
        return stockXClient.queryPrice(productId);
    }
}
