package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
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
                stockXClient.extendItem(node.getString("id"), node.getString("orderNumber"));
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
     * 多账号 Excel 压价：对指定账号的在售商品进行压价
     */
    public void priceDownWithExcelForAccount(StockXAccount account, String inventoryType) {
        String accountId = account.getName();
        String accountName = account.getName();
        long taskStartTime = System.currentTimeMillis();
        int totalPriceDown = 0, totalSkip = 0;

        boolean isStandard = "STANDARD".equals(inventoryType);
        Long taskId = TaskSwitch.getExcelTaskId(accountId, inventoryType);
        int pageNumber = 1;
        boolean hasMore = true;
        int pagesPerBatch = 4;

        while (hasMore) {
            if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) {
                log.info("[{}]{}压价任务已取消", accountName, inventoryType);
                break;
            }

            // ===== 收集本批次（最多4页）的 listing =====
            List<JSONObject> batchItems = new ArrayList<>();
            int pagesCollected = 0;
            while (pagesCollected < pagesPerBatch && hasMore) {
                if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) break;
                JSONObject jsonObject = stockXClient.querySellingItemsByInventoryType(inventoryType, pageNumber, account);
                if (jsonObject == null) {
                    log.error("[{}] priceDownWithExcel querySellingItems failed, inventoryType:{}, page:{}", accountName, inventoryType, pageNumber);
                    hasMore = false;
                    break;
                }
                if (jsonObject.getBooleanValue("_unauthorized")) {
                    log.error("[{}] priceDownWithExcel Token已过期，终止本轮压价", accountName);
                    throw new RuntimeException("TOKEN_EXPIRED");
                }
                List<JSONObject> items = jsonObject.getJSONArray("items").toJavaList(JSONObject.class);
                if (items.isEmpty()) {
                    hasMore = false;
                    break;
                }
                batchItems.addAll(items);
                hasMore = jsonObject.getBoolean("hasMore");
                pageNumber++;
                pagesCollected++;
            }

            if (batchItems.isEmpty()) break;
            log.info("[{}] priceDownWithExcel[{}] 本批次收集{}页共{}条", accountName, inventoryType, pagesCollected, batchItems.size());

            // ===== 预加载得物价格 =====
            Set<String> poisonModelNos = batchItems.stream()
                    .filter(item -> {
                        String sid = item.getString("styleId");
                        String sz = item.getString("size");
                        if (StrUtil.isBlank(sid) || StrUtil.isBlank(sz)) return false;
                        ShoesContext.PriceDownConfig config = ShoesContext.getPriceDownConfig(accountId, inventoryType, sid, sz);
                        return config != null && config.isPoisonCompare();
                    })
                    .map(item -> item.getString("styleId"))
                    .collect(Collectors.toSet());
            if (!poisonModelNos.isEmpty()) {
                priceManager.batchLoadPrices(poisonModelNos);
            }

            // ===== 按 styleId:size 分组（跨页合并） =====
            Map<String, List<JSONObject>> grouped = new LinkedHashMap<>();
            for (JSONObject item : batchItems) {
                String styleId = item.getString("styleId");
                String size = item.getString("size");
                if (StrUtil.isBlank(styleId) || StrUtil.isBlank(size)) {
                    continue;
                }
                String key = STR."\{styleId}:\{size}";
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }

            // ===== 处理本批次所有分组 =====
            List<Map<String, String>> toPriceDown = new ArrayList<>();
            Map<String, Pair<Long, String>> listingToTaskInfo = new HashMap<>();

            for (Map.Entry<String, List<JSONObject>> entry : grouped.entrySet()) {
                if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) break;

                String key = entry.getKey();
                List<JSONObject> listings = entry.getValue();
                String[] parts = key.split(":", 2);
                String styleId = parts[0];
                String size = parts[1];

                ShoesContext.PriceDownConfig config = ShoesContext.getPriceDownConfig(accountId, inventoryType, styleId, size);
                if (config == null) {
                    totalSkip += listings.size();
                    for (JSONObject listing : listings) {
                        Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                        updateTaskItemResult(taskItemId, "跳过-不在Excel中");
                    }
                    continue;
                }
                int excelMinPrice = config.minPrice();
                boolean isPoisonCompare = config.isPoisonCompare();

                listings.sort(Comparator.comparingInt(a -> a.getIntValue("amount")));
                JSONObject bestListing = listings.get(0);

                Integer lowestPrice;
                if (isStandard) {
                    Integer standardLowest = bestListing.getInteger("standardLowest");
                    Integer expressLowest = bestListing.getInteger("expressStandardLowest");
                    if (standardLowest != null && expressLowest != null) {
                        lowestPrice = Math.min(standardLowest, expressLowest);
                    } else {
                        lowestPrice = standardLowest != null ? standardLowest : expressLowest;
                    }
                } else {
                    lowestPrice = bestListing.getInteger("expressStandardLowest");
                }

                Integer poisonPrice = null;
                String euSize = null;
                Integer minExpectProfit = null;
                if (isPoisonCompare) {
                    euSize = bestListing.getString("euSize");
                    if (StrUtil.isNotBlank(euSize)) {
                        poisonPrice = priceManager.getPoisonPrice(styleId, euSize);
                        minExpectProfit = account.getMinProfit();
                    }
                    if (StrUtil.isBlank(euSize) || poisonPrice == null) {
                        for (JSONObject listing : listings) {
                            Long tid = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                            updateTaskItemResult(tid, StrUtil.isBlank(euSize) ? "跳过-无法获取EU码" : "跳过-得物无价");
                        }
                        totalSkip += listings.size();
                        continue;
                    }
                }

                final Integer pp = poisonPrice;
                final Integer mep = minExpectProfit;
                boolean anyIsLowest = lowestPrice != null && lowestPrice > 0
                        && listings.stream().anyMatch(l -> l.getIntValue("amount") <= lowestPrice);

                for (JSONObject listing : listings) {
                    String lid = listing.getString("id");
                    int amt = listing.getIntValue("amount");
                    Long itemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);

                    if (lowestPrice == null || lowestPrice <= 1) {
                        updateTaskItemResult(itemId, "跳过-无最低价");
                        totalSkip++;
                        continue;
                    }

                    int markUpPrice = amt + 100;
                    String markUpAmount = String.valueOf(markUpPrice);

                    if (anyIsLowest) {
                        if (isProfitable(isPoisonCompare, amt, excelMinPrice, pp, mep, account)) {
                            updateTaskItemResult(itemId, amt <= lowestPrice ? "保持-已是最低价" : "跳过-相同货号尺码已有最低价");
                            if (isPoisonCompare) updateTaskItemProfit(itemId, pp, amt, account);
                        } else {
                            toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                            listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                            updateTaskItemResult(itemId, isPoisonCompare ? "待加价$100-不盈利" : "待加价$100-低于Excel最低价");
                            if (isPoisonCompare) updateTaskItemProfit(itemId, pp, amt, account);
                        }
                    } else {
                        if (listing == listings.get(0)) {
                            int newPrice = lowestPrice - 1;
                            if (isProfitable(isPoisonCompare, newPrice, excelMinPrice, pp, mep, account)) {
                                toPriceDown.add(Map.of("listingId", lid, "amount", String.valueOf(newPrice), "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, String.valueOf(newPrice)));
                                updateTaskItemResult(itemId, "待压价");
                                if (isPoisonCompare) updateTaskItemProfit(itemId, pp, newPrice, account);
                            } else if (isProfitable(isPoisonCompare, amt, excelMinPrice, pp, mep, account)) {
                                updateTaskItemResult(itemId, isPoisonCompare ? "保持-压价后不盈利但当前价盈利" : "保持-压价后低于Excel最低价");
                                if (isPoisonCompare) updateTaskItemProfit(itemId, pp, amt, account);
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, isPoisonCompare ? "待加价$100-不盈利" : "待加价$100-低于Excel最低价");
                                if (isPoisonCompare) updateTaskItemProfit(itemId, pp, amt, account);
                            }
                        } else {
                            if (isProfitable(isPoisonCompare, amt, excelMinPrice, pp, mep, account)) {
                                updateTaskItemResult(itemId, "跳过-相同货号尺码");
                                if (isPoisonCompare) updateTaskItemProfit(itemId, pp, amt, account);
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, isPoisonCompare ? "待加价$100-不盈利" : "待加价$100-低于Excel最低价");
                                if (isPoisonCompare) updateTaskItemProfit(itemId, pp, amt, account);
                            }
                        }
                    }
                }
            }

            // ===== 批量提交本批次压价（StockX限制每批最多100条） =====
            if (!toPriceDown.isEmpty() && !TaskSwitch.isExcelCancelled(accountId, inventoryType)) {
                int batchLimit = 100;
                for (int i = 0; i < toPriceDown.size(); i += batchLimit) {
                    if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) break;
                    List<Map<String, String>> subBatch = toPriceDown.subList(i, Math.min(i + batchLimit, toPriceDown.size()));
                    String batchId = stockXClient.batchUpdateListings(subBatch, account);
                    if (batchId != null) {
                        waitForBatchComplete(batchId, account);
                        for (Map<String, String> item : subBatch) {
                            Pair<Long, String> info = listingToTaskInfo.get(item.get("listingId"));
                            if (info != null) {
                                updateTaskItemResult(info.getKey(), "9999".equals(info.getValue()) ? "已设9999" : "压价已提交");
                            }
                        }
                        totalPriceDown += subBatch.size();
                    } else {
                        for (Map<String, String> item : subBatch) {
                            Pair<Long, String> info = listingToTaskInfo.get(item.get("listingId"));
                            if (info != null) {
                                updateTaskItemResult(info.getKey(), "提交失败");
                            }
                        }
                    }
                }
            }
        }

        log.info("[{}] priceDownWithExcel[{}] finished, totalPriceDown:{}, totalSkip:{}, cost:{}",
                accountName, inventoryType, totalPriceDown, totalSkip, TimeUtil.getCostMin(taskStartTime));
    }

    private Long insertTaskItemForAccount(Long taskId, String accountId, String inventoryType, JSONObject item) {
        if (taskId == null) return null;
        int round = TaskSwitch.getExcelRound(accountId, inventoryType);

        TaskItemDO taskItemDO = new TaskItemDO();
        taskItemDO.setTaskId(taskId);
        taskItemDO.setRound(round);
        taskItemDO.setTitle(item.getString("productName"));
        taskItemDO.setListingId(item.getString("id"));
        taskItemDO.setProductId(item.getString("variantId"));
        taskItemDO.setStyleId(item.getString("styleId"));
        taskItemDO.setSize(item.getString("size"));
        taskItemDO.setEuSize(item.getString("euSize"));
        Integer amount = item.getInteger("amount");
        taskItemDO.setCurrentPrice(amount != null ? BigDecimal.valueOf(amount) : null);

        boolean isStandard = "STANDARD".equals(inventoryType);
        Integer lowestPrice;
        if (isStandard) {
            Integer sl = item.getInteger("standardLowest");
            Integer el = item.getInteger("expressStandardLowest");
            lowestPrice = (sl != null && el != null) ? Math.min(sl, el) : (sl != null ? sl : el);
        } else {
            lowestPrice = item.getInteger("expressStandardLowest");
        }
        taskItemDO.setLowestPrice(lowestPrice != null ? BigDecimal.valueOf(lowestPrice) : null);

        taskItemDO.setOperateTime(new Date());
        taskItemDO.setOperateResult("待处理");
        taskItemMapper.insert(taskItemDO);
        return taskItemDO.getId();
    }

    private void waitForBatchComplete(String batchId, StockXAccount account) {
        for (int i = 0; i < 12; i++) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            JSONObject status = stockXClient.queryBatchUpdateStatus(batchId, account);
            if (status != null && "COMPLETED".equals(status.getString("status"))) {
                log.info("[{}] batch update completed, batchId:{}", account.getName(), batchId);
                return;
            }
        }
        log.warn("[{}] batch update timeout, batchId:{}", account.getName(), batchId);
    }

    private boolean isProfitable(boolean isPoisonCompare, int price, int excelMinPrice, Integer poisonPrice, Integer minExpectProfit, StockXAccount account) {
        if (isPoisonCompare) {
            return poisonPrice != null && minExpectProfit != null && ShoesUtil.canStockxEarn(poisonPrice, price, minExpectProfit, account);
        }
        return price >= excelMinPrice;
    }

    private void updateTaskItemProfit(Long taskItemId, int poisonPrice, int sellPrice, StockXAccount account) {
        if (taskItemId == null) return;
        double profit = ShoesUtil.getStockxEarn(poisonPrice, sellPrice, account);
        double profitRate = profit / poisonPrice;
        TaskItemDO update = new TaskItemDO();
        update.setId(taskItemId);
        update.setPoisonPrice(BigDecimal.valueOf(poisonPrice));
        update.setPoison35Price(BigDecimal.valueOf(poisonPrice));
        update.setProfit35(BigDecimal.valueOf(profit).setScale(2, RoundingMode.HALF_UP));
        update.setProfitRate35(BigDecimal.valueOf(profitRate).setScale(4, RoundingMode.HALF_UP));
        taskItemMapper.updateById(update);
    }

}
