package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.util.LimiterHelper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
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
     * 压价任务：对在售商品进行压价或下架
     * 每查询一页就处理一页：并发压价 + 批量下架
     * @return 查询到的所有商品key
     */
    public Set<String> priceDown() {
        long taskStartTime = System.currentTimeMillis();
        int pageNumber = 1;
        boolean hasMore;
        Set<String> allItemKeys = new HashSet<>();
        int totalPriceDown = 0, totalDelete = 0;

        // 创建线程池，整个任务共用
        int threadCount = StockXSwitch.TASK_PRICE_DOWN_THREAD_COUNT;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            do {
                long startTime = System.currentTimeMillis();
                JSONObject jsonObject = stockXClient.querySellingItems(pageNumber, null);
                if (jsonObject == null) {
                    log.error("priceDown querySellingItems failed, page:{}", pageNumber);
                    break;
                }
                List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);
                // 预加载得物价格
                Set<String> modelNos = items.stream()
                        .map(item -> item.getString("styleId"))
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toSet());
                priceManager.preloadMissingPrices(modelNos);

                // 当前页需要压价的商品
                List<Pair<String, Integer>> toPriceDown = new ArrayList<>();
                // 当前页需要下架的商品
                List<String> toDelete = new ArrayList<>();

                for (JSONObject item : items) {
                    String styleId = item.getString("styleId");
                    String euSize = item.getString("euSize");
                    if (StrUtil.isBlank(styleId) || StrUtil.isBlank(euSize)) {
                        log.info("priceDown no styleId or euSize, modelNo:{}, euSize:{}", styleId, euSize);
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
                    if (amount == null || lowestAskAmount == null) {
                        continue;
                    }
                    String id = item.getString("id");
                    Integer minExpectProfit = ShoesUtil.isThreeFiveModel(styleId, euSize) ? PoisonSwitch.MIN_THREE_PROFIT : PoisonSwitch.MIN_PROFIT;

                    // 大于最低价或者已经过期，需要压价
                    if (amount > lowestAskAmount || item.getBoolean("isExpired")) {
                        if (lowestAskAmount > 1) {
                            int newPrice = lowestAskAmount - 1;
                            if (ShoesUtil.canStockxEarn(poisonPrice, newPrice, minExpectProfit)) {
                                // 可以盈利，调用压价接口
                                toPriceDown.add(Pair.of(id, newPrice));
                            } else {
                                // 压价后不盈利，下架
                                toDelete.add(id);
                            }
                        } else {
                            // 没有最低价信息，下架
                            toDelete.add(id);
                        }
                    } else {
                        // 已经是最低价，判断是否盈利
                        if (!ShoesUtil.canStockxEarn(poisonPrice, amount, minExpectProfit)) {
                            toDelete.add(id);
                        }
                    }
                }

                // 当前页：并发调用压价接口
                if (!toPriceDown.isEmpty()) {
                    CountDownLatch latch = new CountDownLatch(toPriceDown.size());
                    for (Pair<String, Integer> priceDownItem : toPriceDown) {
                        executor.submit(() -> {
                            try {
                                LimiterHelper.limitStockxPriceDown();
                                stockXClient.updateSellerListing(priceDownItem.getKey(), String.valueOf(priceDownItem.getValue()));
                            } catch (Exception e) {
                                log.error("priceDown updateSellerListing failed, id:{}, price:{}, error:{}", priceDownItem.getKey(), priceDownItem.getValue(), e.getMessage());
                            } finally {
                                latch.countDown();
                            }
                        });
                    }
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        log.error("priceDown await interrupted", e);
                        Thread.currentThread().interrupt();
                    }
                }

                // 当前页：批量下架
                if (!toDelete.isEmpty()) {
                    stockXClient.deleteItems(toDelete);
                }

                totalPriceDown += toPriceDown.size();
                totalDelete += toDelete.size();
                log.info("priceDown page:{}, items:{}, priceDown:{}, delete:{}, cost:{}",
                        pageNumber, items.size(), toPriceDown.size(), toDelete.size(), TimeUtil.getCostMin(startTime));

                hasMore = jsonObject.getBoolean("hasMore");
                pageNumber++;
            } while (hasMore);
        } finally {
            executor.shutdown();
        }

        log.info("priceDown task finished, allItemKeys:{}, totalPriceDown:{}, totalDelete:{}, totalCost:{}",
                allItemKeys.size(), totalPriceDown, totalDelete, TimeUtil.getCostMin(taskStartTime));
        return allItemKeys;
    }

    /**
     * 下架不赢利的商品，返回查询到的所有商品key
     * @deprecated 使用 {@link #priceDown()} 代替
     */
    @Deprecated
    public Set<String> clearNoBenefitItems() {
        return priceDown();
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
