package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.mapper.StockXItemMapper;
import cn.ken.shoes.mapper.StockXPriceMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SqlHelper;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    public void refreshBrand() {
        List<BrandDO> brandDOList = stockXClient.queryBrands();
        brandMapper.batchInsertOrUpdate(brandDOList);
    }

    @SneakyThrows
    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public int refreshPrices() {
        // 1.下架不赢利的商品
        clearNoBenefitItems();
        // 2.清空绿叉价格
        stockXPriceMapper.delete(new QueryWrapper<>());
        // 3.查询要比价的商品和价格
        int cnt = 0;
        List<BrandDO> brandDOList = brandMapper.selectByPlatform("stockx");
        for (BrandDO brandDO : brandDOList) {
            if (!brandDO.getNeedCrawl()) {
                continue;
            }
            String brand = brandDO.getName();
            Integer crawlCnt = brandDO.getCrawlCnt();
            int crawlPage = (int) Math.ceil(crawlCnt / 100.0);
            for (int i = 1; i <= crawlPage; i++) {
                List<StockXPriceDO> stockXPriceDOList = stockXClient.queryHotItemsByBrandWithPrice(brand, i);
                Thread.startVirtualThread(() -> SqlHelper.batch(stockXPriceDOList, stockXPriceDO -> stockXPriceMapper.insertIgnore(stockXPriceDO)));
                // 4.比价和上架
//                cnt += compareWithPoisonAndChangePrice(stockXPriceDOList);
            }
        }
        return cnt;
    }

    private void clearNoBenefitItems() {
        String afterName = null;
        boolean hasMore;
        do {
            JSONObject jsonObject = stockXClient.querySellingItems(afterName, null);
            List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);
            Set<String> collect = items.stream().map(json -> json.getString("styleId")).collect(Collectors.toSet());
            Map<String, Integer> map = poisonPriceMapper.selectListByModelNos(collect).stream().collect(Collectors.toMap(
                    price -> STR."\{price.getModelNo()}:\{price.getEuSize()}",
                    PoisonPriceDO::getPrice
            ));
            List<Pair<String, Integer>> toDelete = new ArrayList<>();
            for (JSONObject item : items) {
                String styleId = item.getString("styleId");
                if (StrUtil.isBlank(styleId)) {
                    continue;
                }
                Integer euSize = item.getInteger("euSize");
                Integer poisonPrice = map.get(STR."\{styleId}:\{euSize}");
                Integer amount = item.getInteger("amount");
                // 得物无价或无盈利，下架该商品
                if (poisonPrice == null || !ShoesUtil.canStockxEarn(poisonPrice, amount)) {
                    String id = item.getString("id");
                    toDelete.add(Pair.of(id, amount));
                }
            }
            stockXClient.deleteItems(toDelete);
            hasMore = jsonObject.getBoolean("hasMore");
            afterName = jsonObject.getString("afterName");
        } while (hasMore);
    }

    public int compareWithPoisonAndChangePrice(List<StockXPriceDO> stockXPriceDOS) {
        int uploadCnt = 0;
        try {
            // 1.查询得物价格
            Set<String> modelNos = stockXPriceDOS.stream().map(StockXPriceDO::getModelNo).collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(modelNos)) {
                return 0;
            }
            List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectListByModelNos(modelNos);
            // 2.查询对应的货号在得物的价格，如果有两个版本的价格，用新版本的
            Map<String, PoisonPriceDO> poisonPriceDOMap = poisonPriceDOList.stream()
                    .collect(Collectors.toMap(
                            poisonPriceDO -> poisonPriceDO.getModelNo() + ":" + poisonPriceDO.getEuSize(),
                            Function.identity(),
                            (k1, k2) -> k1.getVersion() > k2.getVersion() ? k1 : k2
                    ));
            List<Pair<String, Integer>> toCreate = new ArrayList<>();
            for (StockXPriceDO stockXPriceDO : stockXPriceDOS) {
                String modelNo = stockXPriceDO.getModelNo();
                String euSize = stockXPriceDO.getEuSize();
                PoisonPriceDO poisonPriceDO = poisonPriceDOMap.get(modelNo + ":" + euSize);
                if (poisonPriceDO == null || poisonPriceDO.getPrice() == null || getStockXPrice(stockXPriceDO) == null) {
                    continue;
                }
                boolean canEarn = ShoesUtil.canStockxEarn(poisonPriceDO.getPrice(), getStockXPrice(stockXPriceDO));
                if (!canEarn) {
                    continue;
                }
                toCreate.add(new Pair<>(stockXPriceDO.getVariantId(), getStockXPrice(stockXPriceDO)));
            }
            uploadCnt += toCreate.size();
            stockXClient.createListing(toCreate);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return uploadCnt;
    }

    private Integer getStockXPrice(StockXPriceDO stockXPriceDO) {
        return StockXSwitch.PRICE_TYPE.getPriceFunction().apply(stockXPriceDO);
    }
}
