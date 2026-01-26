package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.mapper.StockXPriceMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SqlHelper;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.builder.ExcelWriterSheetBuilder;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

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

    @Resource
    private SearchTaskMapper searchTaskMapper;

    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.EXTEND_ORDER, operateStatus = TaskDO.OperateStatusEnum.MANUALLY)
    public void extendAllItems() {
        boolean hasMore;
        String afterName = null;
        do {
            JSONObject jsonObject = stockXClient.queryToDeal(afterName);
            if (jsonObject == null) {
                throw new RuntimeException("发生异常");
            }
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
        long now = System.currentTimeMillis();
        Set<String> existingItemKeys = clearNoBenefitItems();
        log.info("finish clearNoBenefitItems, existingItemKeys size:{}, cost:{}", existingItemKeys.size(), TimeUtil.getCostMin(now));
        // 2.清空绿叉价格
        stockXPriceMapper.delete(new QueryWrapper<>());
        // 3.查询要比价的商品和价格
        int allCnt = 0, cnt = 0;
        List<BrandDO> brandDOList = brandMapper.selectByPlatform("stockx");
        for (BrandDO brandDO : brandDOList) {
            now = System.currentTimeMillis();
            if (!brandDO.getNeedCrawl()) {
                continue;
            }
            String brand = brandDO.getName();
            int crawlCnt = Math.min(brandDO.getCrawlCnt(), brandDO.getTotal());
            int crawlPage = (int) Math.ceil(crawlCnt / 50.0);
            for (int i = 1; i <= crawlPage && i <= 20; i++) {
                try {
                    List<StockXPriceDO> stockXPriceDOList = stockXClient.queryHotItemsByBrandWithPrice(brand, i);
                    Thread.startVirtualThread(() -> SqlHelper.batch(stockXPriceDOList, stockXPriceDO -> stockXPriceMapper.insertIgnore(stockXPriceDO)));
                    // 过滤掉已存在的商品和禁爬货号（FLAWS）
                    List<StockXPriceDO> filteredList = stockXPriceDOList.stream()
                            .filter(item -> !existingItemKeys.contains(STR."\{item.getModelNo()}:\{item.getEuSize()}"))
                            .filter(item -> !ShoesContext.isFlawsModel(item.getModelNo(), item.getEuSize()))
                            .toList();
                    // 4.比价和上架
                    cnt += compareWithPoisonAndChangePrice(filteredList);
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
     * 下架不赢利的商品，返回查询到的所有商品key
     */
    @Task(platform = TaskDO.PlatformEnum.STOCKX, taskType = TaskDO.TaskTypeEnum.CLEAR_NO_BENEFIT_ITEMS, operateStatus = TaskDO.OperateStatusEnum.MANUALLY)
    public Set<String> clearNoBenefitItems() {
        int pageNumber = 1;
        boolean hasMore;
        Set<String> allItemKeys = new HashSet<>();
        do {
            long startTime = System.currentTimeMillis();
            JSONObject jsonObject = stockXClient.querySellingItems(pageNumber, null);
            List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);
            // 预加载得物价格
            Set<String> modelNos = items.stream()
                    .map(item -> item.getString("styleId"))
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toSet());
            priceManager.preloadMissingPrices(modelNos);
            List<String> toDelete = new ArrayList<>();
            List<Pair<String, Integer>> toCreate = new ArrayList<>();
            for (JSONObject item : items) {
                String styleId = item.getString("styleId");
                String euSize = item.getString("euSize");
                if (StrUtil.isBlank(styleId) || StrUtil.isBlank(euSize)) {
                    log.info("clearNoBenefitItems no styleId or euSize, modelNo:{}, euSize:{}", styleId, euSize);
                    continue;
                }
                // 记录所有查询到的商品
                allItemKeys.add(STR."\{styleId}:\{euSize}");
                if (ShoesContext.isNotCompareModel(styleId, euSize)) {
                    // 不压价下架的商品
                    continue;
                }
                Integer poisonPrice = priceManager.getPoisonPrice(styleId, euSize);
                if (poisonPrice == null) {
                    // 得物无价，下架
                    toDelete.add(item.getString("id"));
                    continue;
                }
                Integer amount = item.getInteger("amount");
                Integer lowestAskAmount = item.getInteger("lowestAskAmount");
                Boolean isExpired = item.getBoolean("isExpired");
                String id = item.getString("id");
                String variantId = item.getString("variantId");
                Integer minExpectProfit = ShoesUtil.isThreeFiveModel(styleId, euSize) ? PoisonSwitch.MIN_THREE_PROFIT : PoisonSwitch.MIN_PROFIT;
                // 如果过期，或者没过期但价格不是最低价
                if (Boolean.TRUE.equals(isExpired) || !Objects.equals(amount, lowestAskAmount)) {
                    // 下架，之后判断最低价-1是否盈利，如果盈利则上架
                    toDelete.add(id);
                    if (lowestAskAmount != null && lowestAskAmount > 1) {
                        int newPrice = lowestAskAmount - 1;
                        if (ShoesUtil.canStockxEarn(poisonPrice, newPrice, minExpectProfit)) {
                            toCreate.add(Pair.of(variantId, newPrice));
                        }
                    }
                } else {
                    // 没过期且是最低价，判断是否盈利，如果不盈利则下架
                    if (!ShoesUtil.canStockxEarn(poisonPrice, amount, minExpectProfit)) {
                        toDelete.add(id);
                    }
                }
            }
            stockXClient.deleteItems(toDelete);
            stockXClient.createListingV2(toCreate);
            log.info("clearNoBenefitItems end, page:{}, toDelete:{}, toCreate:{}, cost:{}", pageNumber, toDelete.size(), toCreate.size(), TimeUtil.getCostMin(startTime));
            hasMore = jsonObject.getBoolean("hasMore");
            pageNumber++;
        } while (hasMore);
        return allItemKeys;
    }

    public int compareWithPoisonAndChangePrice(List<StockXPriceDO> stockXPriceDOS) {
        int uploadCnt = 0, poisonNoPriceCnt = 0, noBenefitCnt = 0, tooExpensiveCnt = 0, stockXNoPriceCnt = 0;
        // 预加载得物价格
        Set<String> modelNos = stockXPriceDOS.stream().map(StockXPriceDO::getModelNo).collect(Collectors.toSet());
        priceManager.preloadMissingPrices(modelNos);
        try {
            List<Pair<String, Integer>> toCreate = new ArrayList<>();
            for (StockXPriceDO stockXPriceDO : stockXPriceDOS) {
                String modelNo = stockXPriceDO.getModelNo();
                String euSize = stockXPriceDO.getEuSize();
                Integer poisonPrice = priceManager.getPoisonPrice(modelNo, euSize);
                if (poisonPrice == null) {
                    poisonNoPriceCnt++;
                    continue;
                }
                if (poisonPrice > PoisonSwitch.MAX_PRICE) {
                    tooExpensiveCnt++;
                    continue;
                }
                Integer stockxPrice = stockXPriceDO.getLowestAskAmount();
                if (stockxPrice == null) {
                    stockXNoPriceCnt++;
                    continue;
                }
                Integer minExpectProfit = ShoesUtil.isThreeFiveModel(modelNo, euSize) ? PoisonSwitch.MIN_THREE_PROFIT : PoisonSwitch.MIN_PROFIT;
                if (!ShoesUtil.canStockxEarn(poisonPrice, stockxPrice - 1, minExpectProfit)) {
                    noBenefitCnt++;
                    continue;
                }
                toCreate.add(new Pair<>(stockXPriceDO.getVariantId(), stockxPrice - 1));
            }
            uploadCnt += toCreate.size();
            // 上架
            stockXClient.createListingV2(toCreate);
            log.info("总量：{}, 上架数量：{}，得物无价数量：{}，绿叉无价数量：{}，得物太贵数量：{}，不盈利数量：{}",
                    stockXPriceDOS.size(), toCreate.size(),
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
