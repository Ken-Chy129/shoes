package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.StockXPriceMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SqlHelper;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class StockXService {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private PriceManager priceManager;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private StockXPriceMapper stockXPriceMapper;

    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.EXTEND_ORDER, operateStatus = TaskDO.OperateStatusEnum.MANUALLY)
    public void extendAllItems() {
        boolean hasMore;
        String afterName = null;
        do {
            JSONObject jsonObject = stockXClient.queryToDeal(afterName);
            List<JSONObject> nodes = jsonObject.getJSONArray("nodes").toJavaList(JSONObject.class);
            for (JSONObject node : nodes) {
                stockXClient.extendItem(node.getString("id"));
            }
            hasMore = jsonObject.getBoolean("hasMore");
            afterName = jsonObject.getString("endCursor");
        } while (hasMore);
    }

    public void refreshBrand() {
        List<BrandDO> brandDOList = stockXClient.queryBrands();
        brandMapper.batchInsertOrUpdate(brandDOList);
    }

    @SneakyThrows
    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public int refreshPrices() {
        // 1.下架不赢利的商品
        Map<String, Pair<String, Integer>> retainItemsMap = clearNoBenefitItems();
        log.info("finish clearNoBenefitItems");
        // 2.清空绿叉价格
        stockXPriceMapper.delete(new QueryWrapper<>());
        // 3.查询要比价的商品和价格
        int allCnt = 0, cnt = 0;
        List<BrandDO> brandDOList = brandMapper.selectByPlatform("stockx");
        for (BrandDO brandDO : brandDOList) {
            long now = System.currentTimeMillis();
            if (!brandDO.getNeedCrawl()) {
                continue;
            }
            String brand = brandDO.getName();
            int crawlCnt = Math.min(brandDO.getCrawlCnt(), brandDO.getTotal());
            int crawlPage = (int) Math.ceil(crawlCnt / 50.0);
            for (int i = 1; i <= crawlPage; i++) {
                try {
                    List<StockXPriceDO> stockXPriceDOList = stockXClient.queryHotItemsByBrandWithPrice(brand, i);
                    Thread.startVirtualThread(() -> SqlHelper.batch(stockXPriceDOList, stockXPriceDO -> stockXPriceMapper.insertIgnore(stockXPriceDO)));
                    // 4.比价和上架
                    cnt += compareWithPoisonAndChangePrice(retainItemsMap, stockXPriceDOList);
                } catch (Exception e) {
                    log.error("refreshPrices error, msg:{}", e.getMessage(), e);
                }
            }
            log.info("finish refreshPrice, brand:{}, cnt:{}, cost:{}", brand, cnt, TimeUtil.getCostMin(now));
            allCnt += cnt;
            cnt = 0;
        }
        return allCnt;
    }

    /**
     * 下架不赢利的商品，返回仍在上架的商品
     */
    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.CLEAR_NO_BENEFIT_ITEMS, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    private Map<String, Pair<String, Integer>> clearNoBenefitItems() {
        String afterName = null;
        boolean hasMore;
        Map<String, Pair<String, Integer>> retainItemsMap = new HashMap<>();
        do {
            long startTime = System.currentTimeMillis();
            JSONObject jsonObject = stockXClient.querySellingItems(afterName, null);
            List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);
            List<Pair<String, Integer>> toDelete = new ArrayList<>();
            for (JSONObject item : items) {
                String styleId = item.getString("styleId");
                String euSize = item.getString("euSize");
                if (StrUtil.isBlank(styleId) || StrUtil.isBlank(euSize)) {
                    log.info("clearNoBenefitItems no styleId or euSize, modelNo:{}, euSize:{}", styleId, euSize);
                    continue;
                }
                if (ShoesContext.isNotCompareModel(styleId, euSize)) {
                    // 不压价下架的商品
                    continue;
                }
                Integer poisonPrice = priceManager.getPoisonPrice(styleId, euSize);
                Integer amount = item.getInteger("amount");
                String id = item.getString("id");
                // 得物无价或无盈利，下架该商品
                if (poisonPrice == null || !ShoesUtil.canStockxEarn(poisonPrice, amount)) {
                    toDelete.add(Pair.of(id, amount));
                } else {
                    retainItemsMap.put(STR."\{styleId}:\{euSize}", Pair.of(id, amount));
                }
            }
            stockXClient.deleteItems(toDelete);
            log.info("clearNoBenefitItems end, toDelete:{}, cost:{}", toDelete.size(), TimeUtil.getCostMin(startTime));
            hasMore = jsonObject.getBoolean("hasMore");
            afterName = jsonObject.getString("endCursor");
        } while (hasMore);
        return retainItemsMap;
    }

    public int compareWithPoisonAndChangePrice(Map<String, Pair<String, Integer>> retainItemsMap, List<StockXPriceDO> stockXPriceDOS) {
        int uploadCnt = 0, poisonNoPriceCnt = 0, noBenefitCnt = 0, tooExpensiveCnt = 0, stockXNoPriceCnt = 0;
        try {
            List<Pair<String, Integer>> toCreate = new ArrayList<>();
            List<Pair<String, Integer>> toRemove = new ArrayList<>();
            for (StockXPriceDO stockXPriceDO : stockXPriceDOS) {
                String modelNo = stockXPriceDO.getModelNo();
                String euSize = stockXPriceDO.getEuSize();
                Integer poisonPrice = priceManager.getPoisonPrice(modelNo, euSize);
                String key = STR."\{modelNo}:\{euSize}";
                if (poisonPrice == null) {
                    poisonNoPriceCnt++;
                    continue;
                }
                if (poisonPrice > PoisonSwitch.MAX_PRICE) {
                    tooExpensiveCnt++;
                    continue;
                }
                if (getStockXPrice(stockXPriceDO) == null) {
                    stockXNoPriceCnt++;
                    continue;
                }
                if (!ShoesUtil.canStockxEarn(poisonPrice, getStockXPrice(stockXPriceDO))) {
                    noBenefitCnt++;
                    continue;
                }
                // 如果当前已经上架了该商品，则需要进行下架操作
                if (retainItemsMap.containsKey(key)) {
                    toRemove.add(retainItemsMap.get(key));
                }
                toCreate.add(new Pair<>(stockXPriceDO.getVariantId(), getStockXPrice(stockXPriceDO)));
            }
            uploadCnt += toCreate.size();
            // 下架重复商品
            stockXClient.deleteItems(toRemove);
            // 上架
            stockXClient.createListingV2(toCreate);
            log.info("总量：{}, 下架数量：{}， 上架数量：{}，得物无价数量：{}，绿叉无价数量：{}，得物太贵数量：{}，不盈利数量：{}",
                    stockXPriceDOS.size(), toRemove.size(), toCreate.size(),
                    poisonNoPriceCnt, stockXNoPriceCnt, tooExpensiveCnt, noBenefitCnt
            );
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return uploadCnt;
    }

    private Integer getStockXPrice(StockXPriceDO stockXPriceDO) {
        return StockXSwitch.PRICE_TYPE.getPriceFunction().apply(stockXPriceDO);
    }
}
