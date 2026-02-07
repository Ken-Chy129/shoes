package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.mapper.StockXPriceMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Resource
    private TaskItemMapper taskItemMapper;

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
    public void refreshPrices() {
        // todo: 暂未实现
//        // 1.下架不赢利的商品
//        long now = System.currentTimeMillis();
//        Set<String> existingItemKeys = priceDown();
//        log.info("finish clearNoBenefitItems, existingItemKeys size:{}, cost:{}", existingItemKeys.size(), TimeUtil.getCostMin(now));
//        // 2.清空绿叉价格
//        stockXPriceMapper.delete(new QueryWrapper<>());
//        // 3.查询要比价的商品和价格
//        int allCnt = 0, cnt = 0;
//        List<BrandDO> brandDOList = brandMapper.selectByPlatform("stockx");
//        for (BrandDO brandDO : brandDOList) {
//            now = System.currentTimeMillis();
//            if (!brandDO.getNeedCrawl()) {
//                continue;
//            }
//            String brand = brandDO.getName();
//            int crawlCnt = Math.min(brandDO.getCrawlCnt(), brandDO.getTotal());
//            int crawlPage = (int) Math.ceil(crawlCnt / 50.0);
//            for (int i = 1; i <= crawlPage && i <= 20; i++) {
//                try {
//                    List<StockXPriceDO> stockXPriceDOList = stockXClient.queryHotItemsByBrandWithPrice(brand, i);
//                    Thread.startVirtualThread(() -> SqlHelper.batch(stockXPriceDOList, stockXPriceDO -> stockXPriceMapper.insertIgnore(stockXPriceDO)));
//                    // 过滤掉已存在的商品和禁爬货号（FLAWS）
//                    List<StockXPriceDO> filteredList = stockXPriceDOList.stream()
//                            .filter(item -> !existingItemKeys.contains(STR."\{item.getModelNo()}:\{item.getEuSize()}"))
//                            .filter(item -> !ShoesContext.isFlawsModel(item.getModelNo(), item.getEuSize()))
//                            .toList();
//                    // 4.比价和上架
//                    cnt += compareWithPoisonAndChangePrice(filteredList);
//                } catch (Exception e) {
//                    log.error("refreshPrices error, msg:{}", e.getMessage(), e);
//                }
//            }
//            log.info("finish refreshPrice, brand:{}, cnt:{}, cost:{}", brand, cnt, TimeUtil.getCostMin(now));
//            allCnt += cnt;
//            cnt = 0;
//        }
//        return allCnt;
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

        // 获取当前任务ID
        Long taskId = TaskSwitch.CURRENT_STOCK_PRICE_DOWN_TASK_ID;

        // 创建线程池，整个任务共用
        int threadCount = StockXSwitch.TASK_PRICE_DOWN_THREAD_COUNT;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            do {
                // 检查取消状态
                if (TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK) {
                    log.info("StockX压价任务已取消，终止执行");
                    break;
                }

                long startTime = System.currentTimeMillis();
                JSONObject jsonObject = stockXClient.querySellingItems(pageNumber, null);
                if (jsonObject == null) {
                    log.error("priceDown querySellingItems failed, page:{}", pageNumber);
                    break;
                }
                List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);

                // 查询完立即批量入库 TaskItem
                Map<String, Long> listingIdToTaskItemIdMap = new HashMap<>();
                for (JSONObject item : items) {
                    String styleId = item.getString("styleId");
                    String size = item.getString("size");
                    String euSize = item.getString("euSize");
                    String listingId = item.getString("id");
                    Integer amount = item.getInteger("amount");
                    Integer lowestAskAmount = item.getInteger("lowestAskAmount");

                    TaskItemDO taskItemDO = new TaskItemDO();
                    taskItemDO.setTaskId(taskId);
                    taskItemDO.setRound(TaskSwitch.CURRENT_STOCK_PRICE_DOWN_ROUND);
                    taskItemDO.setTitle(item.getString("productName"));
                    taskItemDO.setListingId(listingId);
                    taskItemDO.setProductId(item.getString("variantId"));
                    taskItemDO.setStyleId(styleId);
                    taskItemDO.setSize(size);
                    taskItemDO.setEuSize(euSize);
                    taskItemDO.setCurrentPrice(amount != null ? BigDecimal.valueOf(amount) : null);
                    taskItemDO.setLowestPrice(lowestAskAmount != null ? BigDecimal.valueOf(lowestAskAmount) : null);
                    taskItemDO.setOperateTime(new Date());
                    taskItemDO.setOperateResult("待处理");
                    taskItemMapper.insert(taskItemDO);
                    listingIdToTaskItemIdMap.put(listingId, taskItemDO.getId());
                }

                // 预加载得物价格
                Set<String> modelNos = items.stream()
                        .map(item -> item.getString("styleId"))
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toSet());
                priceManager.preloadMissingPrices(modelNos);

                // 当前页需要压价的商品：listingId -> (newPrice, taskItemId)
                Map<String, Pair<Integer, Long>> toPriceDown = new LinkedHashMap<>();
                // 当前页需要下架的商品：listingId -> taskItemId
                Map<String, Long> toDelete = new LinkedHashMap<>();

                for (JSONObject item : items) {
                    // 检查取消状态
                    if (TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK) {
                        log.info("StockX压价任务已取消，终止处理当前页");
                        break;
                    }
                    String listingId = item.getString("id");
                    Long taskItemId = listingIdToTaskItemIdMap.get(listingId);

                    String styleId = item.getString("styleId");
                    String euSize = item.getString("euSize");
                    if (StrUtil.isBlank(styleId) || StrUtil.isBlank(euSize)) {
                        updateTaskItemResult(taskItemId, "跳过-缺少信息");
                        continue;
                    }

                    // 记录所有查询到的商品
                    allItemKeys.add(STR."\{styleId}:\{euSize}");

                    if (ShoesContext.isNotCompareModel(styleId, euSize)) {
                        // 不压价下架的商品
                        updateTaskItemResult(taskItemId, "跳过-不比价");
                        continue;
                    }

                    Integer amount = item.getInteger("amount");
                    Integer lowestAskAmount = item.getInteger("lowestAskAmount");
                    Integer poisonPrice = priceManager.getPoisonPrice(styleId, euSize);
                    if (poisonPrice == null) {
                        // 得物无价，下架
                        updateTaskItemResult(taskItemId, "待下架-得物无价");
                        toDelete.put(listingId, taskItemId);
                        continue;
                    }

                    if (amount == null || lowestAskAmount == null) {
                        updateTaskItemResult(taskItemId, "跳过-缺少信息");
                        continue;
                    }

                    // 更新得物价格、利润和利润率
                    if (taskItemId != null && lowestAskAmount > 1) {
                        int sellPrice = lowestAskAmount - 1;
                        double profit = ShoesUtil.getStockxEarn(poisonPrice, sellPrice);
                        double profitRate = profit / poisonPrice;

                        TaskItemDO updatePoison = new TaskItemDO();
                        updatePoison.setId(taskItemId);
                        updatePoison.setPoisonPrice(BigDecimal.valueOf(poisonPrice));
                        updatePoison.setPoison35Price(BigDecimal.valueOf(poisonPrice));
                        updatePoison.setProfit35(BigDecimal.valueOf(profit).setScale(2, RoundingMode.HALF_UP));
                        updatePoison.setProfitRate35(BigDecimal.valueOf(profitRate).setScale(4, RoundingMode.HALF_UP));

                        taskItemMapper.updateById(updatePoison);
                    }

                    // 已过期的商品直接下架，不进行压价
                    if (Boolean.TRUE.equals(item.getBoolean("isExpired"))) {
                        updateTaskItemResult(taskItemId, "待下架-已过期");
                        toDelete.put(listingId, taskItemId);
                        continue;
                    }

                    Integer minExpectProfit = ShoesUtil.isThreeFiveModel(styleId, euSize) ? PoisonSwitch.MIN_THREE_PROFIT : PoisonSwitch.MIN_PROFIT;
                    // 大于最低价，需要压价
                    if (amount > lowestAskAmount) {
                        if (lowestAskAmount > 1) {
                            int newPrice = lowestAskAmount - 1;
                            if (ShoesUtil.canStockxEarn(poisonPrice, newPrice, minExpectProfit)) {
                                // 可以盈利，调用压价接口
                                updateTaskItemResult(taskItemId, "待压价");
                                toPriceDown.put(listingId, Pair.of(newPrice, taskItemId));
                            } else {
                                // 压价后不盈利，下架
                                updateTaskItemResult(taskItemId, "待下架-压价后不盈利");
                                toDelete.put(listingId, taskItemId);
                            }
                        } else {
                            // 没有最低价信息，下架
                            updateTaskItemResult(taskItemId, "待下架-无最低价");
                            toDelete.put(listingId, taskItemId);
                        }
                    } else {
                        // 已经是最低价，判断是否盈利
                        if (!ShoesUtil.canStockxEarn(poisonPrice, amount, minExpectProfit)) {
                            updateTaskItemResult(taskItemId, "待下架-不盈利");
                            toDelete.put(listingId, taskItemId);
                        } else {
                            updateTaskItemResult(taskItemId, "保持-已是最低价且盈利");
                        }
                    }
                }

                // 检查取消状态，跳过压价和下架操作
                if (TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK) {
                    log.info("StockX压价任务已取消，跳过压价和下架操作");
                    break;
                }

                // 当前页：并发调用压价接口
                if (!toPriceDown.isEmpty()) {
                    CountDownLatch latch = new CountDownLatch(toPriceDown.size());
                    for (Map.Entry<String, Pair<Integer, Long>> entry : toPriceDown.entrySet()) {
                        String listingId = entry.getKey();
                        int newPrice = entry.getValue().getKey();
                        Long taskItemId = entry.getValue().getValue();
                        executor.submit(() -> {
                            try {
                                // 在每个压价操作前检查状态
                                if (TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK) {
                                    updateTaskItemResult(taskItemId, "取消-任务已终止");
                                    return;
                                }
                                LimiterHelper.limitStockxPriceDown();
                                if (stockXClient.updateSellerListing(listingId, String.valueOf(newPrice))) {
                                    updateTaskItemResult(taskItemId, "压价成功");
                                } else {
                                    updateTaskItemResult(taskItemId, "压价失败");
                                }
                            } catch (Exception e) {
                                log.error("priceDown updateSellerListing failed, id:{}, price:{}, error:{}", listingId, newPrice, e.getMessage());
                                updateTaskItemResult(taskItemId, "压价失败-" + e.getMessage());
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

                // 检查取消状态，跳过下架操作
                if (TaskSwitch.CANCEL_STOCK_PRICE_DOWN_TASK) {
                    log.info("StockX压价任务已取消，跳过下架操作");
                    break;
                }

                // 当前页：批量下架
                if (!toDelete.isEmpty()) {
                    boolean isSuccess = stockXClient.deleteItems(new ArrayList<>(toDelete.keySet()));
                    // 批量更新下架结果
                    String result = isSuccess ? "下架成功" : "下架失败";
                    taskItemMapper.batchUpdateResult(new ArrayList<>(toDelete.values()), result);
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

    /**
     * 更新 TaskItem 操作结果
     */
    private void updateTaskItemResult(Long taskItemId, String result) {
        if (taskItemId == null) {
            return;
        }
        TaskItemDO updateItem = new TaskItemDO();
        updateItem.setId(taskItemId);
        updateItem.setOperateResult(result);
        updateItem.setOperateTime(new Date());
        taskItemMapper.updateById(updateItem);
    }

    /**
     * 下架所有StockX商品
     * 分页查询当前上架的商品，然后批量下架
     */
    public void delistAllItems() {
        long startTime = System.currentTimeMillis();
        int pageNumber = 1;
        boolean hasMore = false;
        int totalDeleted = 0;

        do {
            try {
                JSONObject jsonObject = stockXClient.querySellingItems(pageNumber, null);
                if (jsonObject == null) {
                    log.error("delistAllItems querySellingItems failed, page:{}", pageNumber);
                    continue;
                }

                List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);
                if (items.isEmpty()) {
                    log.info("delistAllItems no items found on page:{}", pageNumber);
                    continue;
                }

                // 提取所有 listing id
                List<String> listingIds = items.stream()
                        .map(item -> item.getString("id"))
                        .filter(StrUtil::isNotBlank)
                        .toList();

                if (!listingIds.isEmpty()) {
                    boolean success = stockXClient.deleteItems(listingIds);
                    if (success) {
                        totalDeleted += listingIds.size();
                        log.info("delistAllItems page:{}, deleted:{} items", pageNumber, listingIds.size());
                    } else {
                        log.error("delistAllItems failed on page:{}", pageNumber);
                    }
                }

                hasMore = jsonObject.getBoolean("hasMore");
                // 注意：因为每次删除后，下一页的数据会前移，所以始终查询第1页
                // pageNumber 保持为1，直到没有更多数据
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        } while (hasMore);

        log.info("delistAllItems finished, totalDeleted:{}, cost:{}", totalDeleted, TimeUtil.getCostMin(startTime));
    }
}
