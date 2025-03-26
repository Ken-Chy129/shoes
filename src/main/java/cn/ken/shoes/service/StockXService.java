package cn.ken.shoes.service;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.StockXItemMapper;
import cn.ken.shoes.mapper.StockXPriceMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.SqlHelper;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

@Slf4j
@Service
public class StockXService {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXItemMapper stockXItemMapper;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private StockXPriceMapper stockXPriceMapper;

    public void refreshBrand() {
        List<BrandDO> brandDOList = stockXClient.queryBrands();
        brandMapper.batchInsertOrUpdate(brandDOList);
    }

    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_ITEMS, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
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
                List<String> productIds = stockXClient.queryHotItemsByBrand(brand, i);
                List<StockXItemDO> stockXItemDOS = productIds.stream().map(productId -> {
                    StockXItemDO stockXItemDO = new StockXItemDO();
                    stockXItemDO.setProductId(productId);
                    return stockXItemDO;
                }).toList();
                SqlHelper.batch(stockXItemDOS, stockXItemDO -> stockXItemMapper.insertIgnore(stockXItemDO));
            }
        }
    }

    @SneakyThrows
    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public void refreshPrices() {
        List<String> productIds = stockXItemMapper.selectAllProductIds();
        for (List<String> partition : Lists.partition(productIds, 5)) {
            List<StockXPriceDO> toInsert = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(5);
            for (String productId : partition) {
                Thread.startVirtualThread(() -> {
                    try {
                        LimiterHelper.limitStockxPrice();
                        List<StockXPriceDO> stockXPriceDOS = stockXClient.queryPrice(productId);
                        toInsert.addAll(stockXPriceDOS);
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            SqlHelper.batch(toInsert, stockXPriceDO -> stockXPriceMapper.insertIgnore(stockXPriceDO));
        }
    }
}
