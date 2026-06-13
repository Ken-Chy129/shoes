package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.mapper.StockXPriceMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.excel.StockXPriceExcel;
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

    @Resource
    private TaskMapper taskMapper;

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
            hasMore = jsonObject.getBooleanValue("hasMore");
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
                hasMore = jsonObject.getBooleanValue("hasMore");
                pageNumber++;
                pagesCollected++;
            }

            if (batchItems.isEmpty()) break;
            log.info("[{}] priceDownWithExcel[{}] 本批次收集{}页共{}条", accountName, inventoryType, pagesCollected, batchItems.size());

            // ===== 预加载得物价格（仅当需要处理Excel外商品时） =====
            if (TaskSwitch.isProcessOutsideExcel(accountId, inventoryType)) {
                Set<String> allModelNos = batchItems.stream()
                        .map(item -> item.getString("styleId"))
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toSet());
                if (!allModelNos.isEmpty()) {
                    priceManager.batchLoadPrices(allModelNos);
                }
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
            List<String> toDelete = new ArrayList<>();
            Map<String, Long> deleteToTaskInfo = new HashMap<>();

            for (Map.Entry<String, List<JSONObject>> entry : grouped.entrySet()) {
                if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) break;

                String key = entry.getKey();
                List<JSONObject> listings = entry.getValue();
                String[] parts = key.split(":", 2);
                String styleId = parts[0];
                String size = parts[1];

                ShoesContext.PriceDownConfig config = ShoesContext.getPriceDownConfig(accountId, inventoryType, styleId, size);

                // ===== Excel 外商品处理 =====
                if (config == null) {
                    boolean processOutside = TaskSwitch.isProcessOutsideExcel(accountId, inventoryType);
                    if (!processOutside) {
                        totalSkip += listings.size();
                        for (JSONObject listing : listings) {
                            Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                            updateTaskItemResult(taskItemId, "跳过-不在Excel中");
                        }
                        continue;
                    }
                    // Excel 外：按得物比价处理
                    String unprofitableAction = TaskSwitch.getUnprofitableAction(accountId, inventoryType);
                    String euSize = listings.get(0).getString("euSize");
                    if (StrUtil.isBlank(euSize)) {
                        totalSkip += listings.size();
                        for (JSONObject listing : listings) {
                            Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                            updateTaskItemResult(taskItemId, "跳过-无法获取EU码");
                        }
                        continue;
                    }
                    Integer poisonPrice = priceManager.getPoisonPrice(styleId, euSize);
                    if (poisonPrice == null) {
                        for (JSONObject listing : listings) {
                            Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                            String lid = listing.getString("id");
                            toDelete.add(lid);
                            deleteToTaskInfo.put(lid, taskItemId);
                            updateTaskItemResult(taskItemId, "待下架-得物无价");
                        }
                        continue;
                    }
                    // 有得物价，按利润判断
                    Integer lowestPrice;
                    if (isStandard) {
                        Integer standardLowest = listings.get(0).getInteger("standardLowest");
                        Integer expressLowest = listings.get(0).getInteger("expressStandardLowest");
                        if (standardLowest != null && expressLowest != null) {
                            lowestPrice = Math.min(standardLowest, expressLowest);
                        } else {
                            lowestPrice = standardLowest != null ? standardLowest : expressLowest;
                        }
                    } else {
                        lowestPrice = listings.get(0).getInteger("expressStandardLowest");
                    }

                    listings.sort(Comparator.comparingInt(a -> a.getIntValue("amount")));
                    int minExpectProfit = account.getMinProfit();
                    boolean anyIsLowest = lowestPrice != null && lowestPrice > 0
                            && listings.stream().anyMatch(l -> l.getIntValue("amount") <= lowestPrice);

                    for (JSONObject listing : listings) {
                        String lid = listing.getString("id");
                        int amt = listing.getIntValue("amount");
                        Long itemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                        updateTaskItemProfit(itemId, poisonPrice, amt, account);

                        if (lowestPrice == null || lowestPrice <= 1) {
                            // 无最低价信息，加价$100
                            int markUpPrice = amt + 100;
                            toPriceDown.add(Map.of("listingId", lid, "amount", String.valueOf(markUpPrice), "currencyCode", "USD"));
                            listingToTaskInfo.put(lid, Pair.of(itemId, String.valueOf(markUpPrice)));
                            updateTaskItemResult(itemId, "待加价$100-无最低价");
                            continue;
                        }

                        boolean profitable = ShoesUtil.canStockxEarn(poisonPrice, amt, minExpectProfit, account);
                        int markUpPrice = amt + 100;
                        String markUpAmount = String.valueOf(markUpPrice);

                        if (anyIsLowest && amt <= lowestPrice) {
                            // 已是最低价
                            if (profitable) {
                                updateTaskItemResult(itemId, "保持-已是最低价且盈利");
                            } else if ("delist".equals(unprofitableAction)) {
                                toDelete.add(lid);
                                deleteToTaskInfo.put(lid, itemId);
                                updateTaskItemResult(itemId, "待下架-不盈利");
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, "待加价$100-不盈利");
                            }
                        } else if (!anyIsLowest && listing == listings.get(0)) {
                            // 不是最低价，尝试压价
                            int newPrice = lowestPrice - 1;
                            boolean newProfitable = ShoesUtil.canStockxEarn(poisonPrice, newPrice, minExpectProfit, account);
                            if (newProfitable) {
                                toPriceDown.add(Map.of("listingId", lid, "amount", String.valueOf(newPrice), "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, String.valueOf(newPrice)));
                                updateTaskItemResult(itemId, "待压价");
                                updateTaskItemProfit(itemId, poisonPrice, newPrice, account);
                            } else if (profitable) {
                                updateTaskItemResult(itemId, "保持-压价后不盈利但当前价盈利");
                            } else if ("delist".equals(unprofitableAction)) {
                                toDelete.add(lid);
                                deleteToTaskInfo.put(lid, itemId);
                                updateTaskItemResult(itemId, "待下架-不盈利");
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, "待加价$100-不盈利");
                            }
                        } else {
                            // 相同货号尺码，非最优listing
                            if (profitable) {
                                updateTaskItemResult(itemId, "跳过-相同货号尺码");
                            } else if ("delist".equals(unprofitableAction)) {
                                toDelete.add(lid);
                                deleteToTaskInfo.put(lid, itemId);
                                updateTaskItemResult(itemId, "待下架-不盈利");
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, "待加价$100-不盈利");
                            }
                        }
                    }
                    continue;
                }

                // ===== Excel 内商品处理 =====
                if (config.skip()) {
                    totalSkip += listings.size();
                    for (JSONObject listing : listings) {
                        Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                        updateTaskItemResult(taskItemId, "跳过-Excel设为跳过");
                    }
                    continue;
                }

                int excelMinPrice = config.minPrice();
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
                        if (amt >= excelMinPrice) {
                            updateTaskItemResult(itemId, amt <= lowestPrice ? "保持-已是最低价" : "跳过-相同货号尺码已有最低价");
                        } else {
                            toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                            listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                            updateTaskItemResult(itemId, "待加价$100-低于Excel最低价");
                        }
                    } else {
                        if (listing == listings.get(0)) {
                            int newPrice = lowestPrice - 1;
                            if (newPrice >= excelMinPrice) {
                                toPriceDown.add(Map.of("listingId", lid, "amount", String.valueOf(newPrice), "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, String.valueOf(newPrice)));
                                updateTaskItemResult(itemId, "待压价");
                            } else if (amt >= excelMinPrice) {
                                updateTaskItemResult(itemId, "保持-压价后低于Excel最低价");
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, "待加价$100-低于Excel最低价");
                            }
                        } else {
                            if (amt >= excelMinPrice) {
                                updateTaskItemResult(itemId, "跳过-相同货号尺码");
                            } else {
                                toPriceDown.add(Map.of("listingId", lid, "amount", markUpAmount, "currencyCode", "USD"));
                                listingToTaskInfo.put(lid, Pair.of(itemId, markUpAmount));
                                updateTaskItemResult(itemId, "待加价$100-低于Excel最低价");
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
                    boolean success = stockXClient.batchUpdateListingsGraphql(subBatch, account);
                    if (success) {
                        for (Map<String, String> item : subBatch) {
                            Pair<Long, String> info = listingToTaskInfo.get(item.get("listingId"));
                            if (info != null) {
                                updateTaskItemResult(info.getKey(), "压价已提交");
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

            // ===== 批量下架 =====
            if (!toDelete.isEmpty() && !TaskSwitch.isExcelCancelled(accountId, inventoryType)) {
                boolean isSuccess = stockXClient.deleteItems(toDelete, account);
                String result = isSuccess ? "下架成功" : "下架失败";
                for (String lid : toDelete) {
                    Long tid = deleteToTaskInfo.get(lid);
                    if (tid != null) {
                        updateTaskItemResult(tid, result);
                    }
                }
            }
        }

        log.info("[{}] priceDownWithExcel[{}] finished, totalPriceDown:{}, totalSkip:{}, cost:{}",
                accountName, inventoryType, totalPriceDown, totalSkip, TimeUtil.getCostMin(taskStartTime));
    }

    // ==================== 搜索上架 ====================

    public boolean searchAndList(StockXAccount account, Long taskId, String keywords, String sorts,
                                 int pageCount, String searchType, int maxListCount) {
        String accountName = account.getName();
        String country = account.getCountry() != null ? account.getCountry() : "US";
        int minExpectProfit = account.getMinProfit();

        // 计算预估总步数（关键词数 × 排序数 × 页数）
        String[] keywordArr = keywords.split("\n");
        String[] sortArr = sorts.split(",");
        long validKeywords = Arrays.stream(keywordArr).map(String::trim).filter(s -> !s.isEmpty()).count();
        long validSorts = Arrays.stream(sortArr).map(String::trim).filter(s -> !s.isEmpty()).count();
        int totalSteps = (int) (validKeywords * validSorts * pageCount);
        int currentStep = 0;

        // 1. 构建已在售 variantId 集合（去重用）
        Set<String> existingVariantIds = new HashSet<>();
        updateSearchProgress(taskId, 0, totalSteps, currentStep, 0, "正在收集已在售商品...");
        log.info("[{}] 搜索上架：开始收集已在售商品...", accountName);
        int page = 1;
        boolean hasMore = true;
        while (hasMore) {
            if (TaskSwitch.isSearchListCancelled(accountName)) return false;
            JSONObject result = stockXClient.querySellingItemsByInventoryType("STANDARD", page, account);
            if (result == null || result.getBooleanValue("_unauthorized")) break;
            com.alibaba.fastjson.JSONArray itemsArr = result.getJSONArray("items");
            if (itemsArr == null || itemsArr.isEmpty()) break;
            List<JSONObject> items = itemsArr.toJavaList(JSONObject.class);
            for (JSONObject item : items) {
                String variantId = item.getString("variantId");
                if (variantId != null) existingVariantIds.add(variantId);
            }
            hasMore = result.getBooleanValue("hasMore");
            page++;
        }
        log.info("[{}] 搜索上架：已在售商品{}条", accountName, existingVariantIds.size());

        // 2. 搜索并处理
        int totalProcessed = 0;
        int totalListed = 0;
        Set<String> processedVariantIds = new HashSet<>();
        List<Pair<String, Integer>> toList = new ArrayList<>();
        Map<String, Long> variantToTaskItemId = new HashMap<>();
        boolean reachedLimit = false;

        for (String keyword : keywordArr) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) continue;
            if (TaskSwitch.isSearchListCancelled(accountName)) break;

            for (String sort : sortArr) {
                sort = sort.trim();
                if (sort.isEmpty()) continue;

                for (int pageIdx = 1; pageIdx <= pageCount; pageIdx++) {
                    if (TaskSwitch.isSearchListCancelled(accountName)) break;
                    currentStep++;
                    int progress = totalSteps > 0 ? Math.min(currentStep * 100 / totalSteps, 99) : 0;
                    String detail = STR."关键词: \{keyword} | 排序: \{sort} | 页码: \{pageIdx}/\{pageCount}";
                    updateSearchProgress(taskId, progress, totalSteps, currentStep, totalListed, detail);

                    Pair<Integer, List<StockXPriceExcel>> searchResult =
                            stockXClient.searchItemWithPrice(keyword, pageIdx, sort, searchType, country, account);
                    if (searchResult == null) continue;

                    List<StockXPriceExcel> items = searchResult.getValue();
                    if (items == null || items.isEmpty()) break;

                    // 批量预加载得物价格
                    Set<String> modelNos = items.stream()
                            .map(StockXPriceExcel::getModelNo)
                            .filter(m -> m != null && !m.isEmpty())
                            .collect(Collectors.toSet());
                    if (!modelNos.isEmpty()) {
                        priceManager.batchLoadPrices(modelNos);
                    }

                    for (StockXPriceExcel item : items) {
                        if (TaskSwitch.isSearchListCancelled(accountName)) break;
                        totalProcessed++;

                        String variantId = item.getId();
                        if (variantId == null || processedVariantIds.contains(variantId)) continue;
                        processedVariantIds.add(variantId);

                        String modelNo = item.getModelNo();
                        String euSize = item.getEuSize();
                        Integer lowestAsk = item.getPrice();

                        // 插入 TaskItemDO
                        TaskItemDO taskItemDO = new TaskItemDO();
                        taskItemDO.setTaskId(taskId);
                        taskItemDO.setRound(1);
                        taskItemDO.setTitle(item.getTitle());
                        taskItemDO.setProductId(variantId);
                        taskItemDO.setStyleId(modelNo);
                        taskItemDO.setSize(item.getUsmSize());
                        taskItemDO.setEuSize(euSize);
                        taskItemDO.setLowestPrice(lowestAsk != null ? BigDecimal.valueOf(lowestAsk) : null);
                        taskItemDO.setOperateTime(new Date());

                        // 跳过无价格
                        if (lowestAsk == null || lowestAsk <= 1) {
                            taskItemDO.setOperateResult("跳过-无最低价");
                            taskItemMapper.insert(taskItemDO);
                            continue;
                        }

                        // 已在售检查
                        if (existingVariantIds.contains(variantId)) {
                            taskItemDO.setCurrentPrice(BigDecimal.valueOf(lowestAsk));
                            taskItemDO.setOperateResult("跳过-已在售");
                            taskItemMapper.insert(taskItemDO);
                            continue;
                        }

                        // 得物价格
                        Integer poisonPrice = priceManager.getPoisonPrice(modelNo, euSize);
                        if (poisonPrice == null) {
                            taskItemDO.setOperateResult("跳过-得物无价");
                            taskItemMapper.insert(taskItemDO);
                            continue;
                        }

                        int listPrice = lowestAsk - 1;
                        taskItemDO.setCurrentPrice(BigDecimal.valueOf(listPrice));
                        taskItemDO.setPoisonPrice(BigDecimal.valueOf(poisonPrice));
                        taskItemDO.setPoison35Price(BigDecimal.valueOf(poisonPrice));

                        double profit = ShoesUtil.getStockxEarn(poisonPrice, listPrice, account);
                        double profitRate = poisonPrice > 0 ? profit / poisonPrice : 0;
                        taskItemDO.setProfit35(BigDecimal.valueOf(profit).setScale(2, RoundingMode.HALF_UP));
                        taskItemDO.setProfitRate35(BigDecimal.valueOf(profitRate).setScale(4, RoundingMode.HALF_UP));

                        boolean profitable = ShoesUtil.canStockxEarn(poisonPrice, listPrice, minExpectProfit, account);
                        if (!profitable) {
                            taskItemDO.setOperateResult("跳过-不盈利");
                            taskItemMapper.insert(taskItemDO);
                            continue;
                        }

                        taskItemDO.setOperateResult("待上架");
                        taskItemMapper.insert(taskItemDO);
                        toList.add(Pair.of(variantId, listPrice));
                        variantToTaskItemId.put(variantId, taskItemDO.getId());
                        existingVariantIds.add(variantId);
                        totalListed++;

                        if (maxListCount > 0 && totalListed >= maxListCount) {
                            batchCreateListings(toList, variantToTaskItemId, account);
                            toList.clear();
                            variantToTaskItemId.clear();
                            reachedLimit = true;
                            log.info("[{}] 搜索上架达到上限({}/{}条)，停止搜索", accountName, totalListed, maxListCount);
                            break;
                        }

                        if (toList.size() >= 50) {
                            batchCreateListings(toList, variantToTaskItemId, account);
                            toList.clear();
                            variantToTaskItemId.clear();
                        }
                    }

                    if (reachedLimit) break;
                    int totalPages = searchResult.getKey();
                    if (pageIdx >= totalPages) break;
                }
                if (reachedLimit) break;
            }
            if (reachedLimit) break;
        }

        // 3. 处理剩余待上架
        if (!toList.isEmpty()) {
            batchCreateListings(toList, variantToTaskItemId, account);
        }

        if (reachedLimit) {
            String detail = STR."达到上架上限(\{maxListCount}条)";
            int progress = totalSteps > 0 ? Math.min(currentStep * 100 / totalSteps, 99) : 0;
            updateSearchProgress(taskId, progress, totalSteps, currentStep, totalListed, detail);
        } else {
            updateSearchProgress(taskId, 100, totalSteps, currentStep, totalListed, "完成");
        }

        log.info("[{}] 搜索上架完成，共处理{}条，上架{}条", accountName, totalProcessed, totalListed);
        return reachedLimit;
    }

    private void updateSearchProgress(Long taskId, int progress, int totalSteps, int currentStep, int listed, String detail) {
        if (taskId == null) return;
        JSONObject attrs = new JSONObject();
        attrs.put("progress", progress);
        attrs.put("total", totalSteps);
        attrs.put("current", currentStep);
        attrs.put("listed", listed);
        attrs.put("detail", detail);
        taskMapper.updateTaskAttributes(taskId, attrs.toJSONString());
    }

    private void batchCreateListings(List<Pair<String, Integer>> items,
                                     Map<String, Long> variantToTaskItemId,
                                     StockXAccount account) {
        String batchId = stockXClient.createListingV2(items, account);
        if (batchId != null) {
            waitForCreateBatchComplete(batchId, account);
            for (Pair<String, Integer> item : items) {
                Long taskItemId = variantToTaskItemId.get(item.getKey());
                if (taskItemId != null) {
                    updateTaskItemResult(taskItemId, "已上架");
                }
            }
            log.info("[{}] 批量上架{}条, batchId:{}", account.getName(), items.size(), batchId);
        } else {
            for (Pair<String, Integer> item : items) {
                Long taskItemId = variantToTaskItemId.get(item.getKey());
                if (taskItemId != null) {
                    updateTaskItemResult(taskItemId, "上架失败");
                }
            }
            log.error("[{}] 批量上架失败, 共{}条", account.getName(), items.size());
        }
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

    private void waitForCreateBatchComplete(String batchId, StockXAccount account) {
        for (int i = 0; i < 12; i++) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stockXClient.queryListing(batchId, account)) {
                log.info("[{}] batch create completed, batchId:{}", account.getName(), batchId);
                return;
            }
        }
        log.warn("[{}] batch create timeout, batchId:{}", account.getName(), batchId);
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

    private boolean isProfitable(int price, int excelMinPrice) {
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
