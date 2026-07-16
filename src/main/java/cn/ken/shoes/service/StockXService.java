package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.TaskCancelledException;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockXService {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXShippingExtensionService shippingExtensionService;

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
        shippingExtensionService.extendAllEnabledAccounts();
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
        updateTaskItemResult(taskItemId, result, null);
    }

    /** 更新结果，并可选地记录压价目标价(targetPrice 非空时写入，供后续对账比对 amount==目标价) */
    private void updateTaskItemResult(Long taskItemId, String result, Integer targetPrice) {
        if (taskItemId == null) {
            return;
        }
        TaskItemDO updateItem = new TaskItemDO();
        updateItem.setId(taskItemId);
        updateItem.setOperateResult(result);
        updateItem.setOperateTime(new Date());
        if (targetPrice != null) {
            updateItem.setTargetPrice(BigDecimal.valueOf(targetPrice));
        }
        taskItemMapper.updateById(updateItem);
    }

    /** 解析压价目标价(listingToTaskInfo 的 value)，解析失败返回 null */
    private static Integer parseTarget(Pair<Long, String> info) {
        if (info == null) {
            return null;
        }
        try {
            return Integer.valueOf(info.getValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 压价校验轮询：最多查 5 次，每次间隔 2 秒，等待 StockX 异步同步完成 */
    private static final int PRICE_DOWN_VERIFY_MAX_ATTEMPTS = 5;
    private static final long PRICE_DOWN_VERIFY_DELAY_MS = 2000;

    /** 上架校验轮询：最多查 18 次、每次间隔 5 秒(约 90s，全部落定即提前结束)，等待 StockX 异步创建 listing 落地 */
    private static final int CREATE_VERIFY_MAX_ATTEMPTS = 18;
    private static final long CREATE_VERIFY_DELAY_MS = 5000;

    /**
     * 上架校验后台线程池：提交上架(QUEUED)后不阻塞搜索上架主任务，由后台线程按 variantID 异步回查并回填结果。
     * 有界(3线程+大队列)：校验读请求仍走账号级 1qps 限流，限制并发数避免把搜索读饿死；队列满时回退到调用线程执行(背压)。
     */
    private final ExecutorService listingVerifyExecutor = new ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2000),
            r -> {
                Thread t = new Thread(r, "StockX-ListVerify");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 提交压价后按 batchId 回查校验该批是否真正同步（synced），把每条明细从"已提交"升级为真实结果。
     * StockX 压价是异步的：mutation 只返回 QUEUED，需回查 listing 的 synced/lastOperation 才知道是否真正生效。
     *
     * @return 本批真实压价成功(已同步)的条数
     */
    private int verifyPriceDownBatch(String batchId, StockXAccount account, String inventoryType,
                                     List<Map<String, String>> subBatch,
                                     Map<String, Pair<Long, String>> listingToTaskInfo) {
        // 先标记"压价已提交"作为中间态（校验若中途异常，至少不会是 null）
        for (Map<String, String> item : subBatch) {
            Pair<Long, String> info = listingToTaskInfo.get(item.get("listingId"));
            if (info != null) {
                updateTaskItemResult(info.getKey(), "压价已提交");
            }
        }

        Set<String> pending = new HashSet<>();
        for (Map<String, String> item : subBatch) {
            pending.add(item.get("listingId"));
        }
        Map<String, JSONObject> states = new HashMap<>();
        int attemptsUsed = 0;
        int queryFails = 0;
        for (int attempt = 1; attempt <= PRICE_DOWN_VERIFY_MAX_ATTEMPTS && !pending.isEmpty(); attempt++) {
            if (TaskSwitch.isExcelCancelled(account.getName(), inventoryType)) {
                break;
            }
            attemptsUsed = attempt;
            // 按 listingId 查当前真实状态(不再用会衰减的 batchId 视图)：只查还没确认的那几条
            Map<String, JSONObject> current = stockXClient.verifyListingsByListingIds(new ArrayList<>(pending), account);
            if (current == null) {
                queryFails++;
                log.warn("[{}] 压价校验第{}轮查询失败, batchId:{}, 待确认{}条", account.getName(), attempt, batchId, pending.size());
            }
            if (current != null) {
                states.putAll(current);
                // 只把"已落定"的移出待确认队列：真报错(errorCode) 或 价已到位(amount==target)。
                // 不能只看 synced——在售挂单提交更新后仍是 synced(同步在旧价)，新价传播要时间，
                // 过早按 synced 收尾会读到旧价、误判"价格未生效"。未落定的留在队列，用后续轮次等价真到位。
                pending.removeIf(id -> {
                    JSONObject node = current.get(id);
                    if (node == null) {
                        return false;
                    }
                    JSONObject lastOp = node.getJSONObject("lastOperation");
                    if (lastOp != null && lastOp.getString("errorCode") != null) {
                        return true; // 真失败，终态
                    }
                    Integer tgt = parseTarget(listingToTaskInfo.get(id));
                    if (tgt == null) {
                        // 目标价解析不了：退回按 synced 判定，避免死等
                        return Boolean.TRUE.equals(node.getBoolean("synced"));
                    }
                    Integer amount = node.getInteger("amount");
                    return amount != null && amount.equals(tgt);
                });
            }
            if (pending.isEmpty() || attempt == PRICE_DOWN_VERIFY_MAX_ATTEMPTS) {
                break;
            }
            // 分片等待，期间响应取消
            long waited = 0;
            while (waited < PRICE_DOWN_VERIFY_DELAY_MS) {
                if (TaskSwitch.isExcelCancelled(account.getName(), inventoryType)) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(500, PRICE_DOWN_VERIFY_DELAY_MS - waited));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waited += 500;
            }
        }

        int success = 0, unsynced = 0, unconfirmed = 0, clampFail = 0, opFail = 0;
        for (Map<String, String> item : subBatch) {
            String listingId = item.get("listingId");
            Pair<Long, String> info = listingToTaskInfo.get(listingId);
            if (info == null) {
                continue;
            }
            Integer target = parseTarget(info); // 解析失败(理论上不会)返回 null：跳过 amount 比对
            JSONObject node = states.get(listingId);
            String resultText;
            if (node == null) {
                resultText = "压价未确认";
                unconfirmed++;
            } else {
                JSONObject lastOp = node.getJSONObject("lastOperation");
                String errCode = lastOp != null ? lastOp.getString("errorCode") : null;
                Integer amount = node.getInteger("amount");
                if (errCode != null) {
                    // 真失败优先
                    String msg = lastOp.getString("displayMessage");
                    resultText = "压价失败:" + (StrUtil.isNotBlank(msg) ? msg : errCode);
                    opFail++;
                } else if (!Boolean.TRUE.equals(node.getBoolean("synced"))) {
                    resultText = "压价未同步";
                    unsynced++;
                } else if (target != null && amount != null && !amount.equals(target)) {
                    // synced=true 但生效价≠目标价：多是新价还没传播(内联窗口太短)，也可能真被钳制/丢弃。
                    // 不在此武断判失败，转中间态交对账；超时(30min)仍不到位再判"超时未生效"。
                    resultText = "压价价格未生效";
                    clampFail++;
                } else {
                    // 写上最终生效价(此时 amount==target)，方便直接看压到了多少
                    resultText = "压价成功($" + amount + ")";
                    success++;
                }
            }
            updateTaskItemResult(info.getKey(), resultText, target);
        }
        log.info("[{}] 压价批次校验完成, batchId:{}, 提交:{}, 用了{}/{}轮(查询失败{}次), 成功:{}, 未同步(转对账):{}, 未确认(转对账):{}, 价没到位(转对账):{}, 报错失败:{}",
                account.getName(), batchId, subBatch.size(), attemptsUsed, PRICE_DOWN_VERIFY_MAX_ATTEMPTS, queryFails,
                success, unsynced, unconfirmed, clampFail, opFail);
        return success;
    }

    /**
     * 多账号 Excel 压价：对指定账号的在售商品进行压价
     */
    public void priceDownWithExcelForAccount(StockXAccount account, String inventoryType) {
        String accountId = account.getName();
        String accountName = account.getName();
        long taskStartTime = System.currentTimeMillis();
        int totalPriceDown = 0, totalSkip = 0;

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
                    if (ShoesContext.isFlawsModel(styleId, euSize)) {
                        totalSkip += listings.size();
                        for (JSONObject listing : listings) {
                            Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                            updateTaskItemResult(taskItemId, "跳过-禁爬货号");
                        }
                        continue;
                    }
                    if (ShoesContext.isNotCompareModel(styleId, euSize)) {
                        totalSkip += listings.size();
                        for (JSONObject listing : listings) {
                            Long taskItemId = insertTaskItemForAccount(taskId, accountId, inventoryType, listing);
                            updateTaskItemResult(taskItemId, "跳过-不比价货号");
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
                    Integer lowestPrice = ShoesUtil.resolveStockxLowest(inventoryType,
                            listings.get(0).getInteger("standardLowest"),
                            listings.get(0).getInteger("expressStandardLowest"));

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

                Integer lowestPrice = ShoesUtil.resolveStockxLowest(inventoryType,
                        bestListing.getInteger("standardLowest"),
                        bestListing.getInteger("expressStandardLowest"));

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
                    String batchId;
                    try {
                        batchId = stockXClient.batchUpdateListingsGraphql(subBatch, account);
                    } catch (StockXRateLimitException | TaskCancelledException e) {
                        throw e; // 限流冷却耗尽 / 取消：交给本轮与 runner 处理，不当作单批失败
                    } catch (RuntimeException e) {
                        if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                            throw e; // Token 过期：终止本轮，runner 置任务失败"Token已过期"
                        }
                        // 其它失败：本批软失败并写入具体原因，继续下一批
                        String reason = e.getMessage() != null ? e.getMessage() : "提交失败";
                        if (reason.length() > 100) {
                            reason = reason.substring(0, 100);
                        }
                        for (Map<String, String> item : subBatch) {
                            Pair<Long, String> info = listingToTaskInfo.get(item.get("listingId"));
                            if (info != null) {
                                updateTaskItemResult(info.getKey(), reason);
                            }
                        }
                        continue;
                    }
                    // 已受理(QUEUED)，按 batchId 回查校验是否真正同步，得到真实成功/失败
                    int confirmed = verifyPriceDownBatch(batchId, account, inventoryType, subBatch, listingToTaskInfo);
                    totalPriceDown += confirmed;
                }
            }

            // ===== 批量下架 =====
            if (!toDelete.isEmpty() && !TaskSwitch.isExcelCancelled(accountId, inventoryType)) {
                // StockX 每批上限 100，此处按 50/批分批下架；每批各自回查校验，结果汇总
                int delBatchLimit = 50;
                Map<String, String> delResult = new HashMap<>();
                for (int i = 0; i < toDelete.size(); i += delBatchLimit) {
                    if (TaskSwitch.isExcelCancelled(accountId, inventoryType)) {
                        break;
                    }
                    List<String> delChunk = toDelete.subList(i, Math.min(i + delBatchLimit, toDelete.size()));
                    String delBatchId = null;
                    String delFailReason = null;
                    try {
                        delBatchId = stockXClient.deleteItems(delChunk, account);
                    } catch (StockXRateLimitException | TaskCancelledException e) {
                        throw e; // 限流冷却耗尽 / 取消：交给本轮与 runner 处理
                    } catch (RuntimeException e) {
                        if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                            throw e; // Token 过期：终止本轮
                        }
                        delFailReason = e.getMessage() != null ? e.getMessage() : "下架失败";
                        if (delFailReason.length() > 100) {
                            delFailReason = delFailReason.substring(0, 100);
                        }
                    }
                    if (delFailReason != null) {
                        for (String lid : delChunk) {
                            delResult.put(lid, delFailReason);
                        }
                    } else {
                        // 已受理(QUEUED)，按 batchId 回查校验是否真正下架
                        delResult.putAll(stockXClient.verifyDeleteBatch(delBatchId, delChunk, account,
                                () -> TaskSwitch.isExcelCancelled(accountId, inventoryType)));
                    }
                }
                for (String lid : toDelete) {
                    Long tid = deleteToTaskInfo.get(lid);
                    if (tid != null) {
                        updateTaskItemResult(tid, delResult.getOrDefault(lid, "下架未确认"));
                    }
                }
            }
        }

        log.info("[{}] priceDownWithExcel[{}] finished, totalPriceDown:{}, totalSkip:{}, cost:{}",
                accountName, inventoryType, totalPriceDown, totalSkip, TimeUtil.getCostMin(taskStartTime));
    }

    // ==================== 搜索上架 ====================

    public boolean searchAndList(StockXAccount account, Long taskId, String keywords, String sorts,
                                 int pageCount, String searchType, int maxListCount, boolean modelNoSearch) {
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
        updateSearchProgress(taskId, 0, totalSteps, currentStep, 0, 0, 0, (int) validKeywords, "正在收集已在售商品...");
        log.info("[{}] 搜索上架：开始收集已在售商品...", accountName);
        int page = 1;
        boolean hasMore = true;
        while (hasMore) {
            if (TaskSwitch.isSearchListCancelled(accountName)) return false;
            JSONObject result = stockXClient.querySellingItemsByInventoryType("STANDARD", page, account);
            if (result == null) break;
            if (result.getBooleanValue("_unauthorized")) {
                throw new RuntimeException("StockX Token已过期或无效，请更新Token");
            }
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
        int keywordIdx = 0;
        Set<String> processedVariantIds = new HashSet<>();
        List<Pair<String, Integer>> toList = new ArrayList<>();
        Map<String, Long> variantToTaskItemId = new HashMap<>();
        boolean reachedLimit = false;

        for (String keyword : keywordArr) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) continue;
            keywordIdx++;
            if (TaskSwitch.isSearchListCancelled(accountName)) break;

            for (String sort : sortArr) {
                sort = sort.trim();
                if (sort.isEmpty()) continue;

                for (int pageIdx = 1; pageIdx <= pageCount; pageIdx++) {
                    if (TaskSwitch.isSearchListCancelled(accountName)) break;
                    currentStep++;
                    int progress = totalSteps > 0 ? Math.min(currentStep * 100 / totalSteps, 99) : 0;
                    String detail = STR."关键词: \{keyword} | 排序: \{sort} | 页码: \{pageIdx}/\{pageCount}";
                    updateSearchProgress(taskId, progress, totalSteps, currentStep, totalListed, totalProcessed, keywordIdx, (int) validKeywords, detail);

                    Pair<Integer, List<StockXPriceExcel>> searchResult =
                            stockXClient.searchItemWithPrice(keyword, pageIdx, sort, searchType, country, account);
                    if (searchResult == null) {
                        throw new RuntimeException("StockX Token已过期或无效，请更新Token");
                    }

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
                        if (modelNoSearch && (modelNo == null || !modelNo.equalsIgnoreCase(keyword))) {
                            continue;
                        }
                        String euSize = item.getEuSize();
                        Integer lowestAsk = item.getPrice();

                        // 插入 TaskItemDO
                        TaskItemDO taskItemDO = new TaskItemDO();
                        taskItemDO.setTaskId(taskId);
                        taskItemDO.setRound(1);
                        taskItemDO.setTitle(item.getTitle());
                        taskItemDO.setBrand(item.getBrand());
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

                        // 禁爬/不比价检查
                        if (ShoesContext.isFlawsModel(modelNo, euSize)) {
                            taskItemDO.setOperateResult("跳过-禁爬货号");
                            taskItemMapper.insert(taskItemDO);
                            continue;
                        }
                        if (ShoesContext.isNotCompareModel(modelNo, euSize)) {
                            taskItemDO.setOperateResult("跳过-不比价货号");
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
            updateSearchProgress(taskId, progress, totalSteps, currentStep, totalListed, totalProcessed, keywordIdx, (int) validKeywords, detail);
        } else {
            updateSearchProgress(taskId, 100, totalSteps, currentStep, totalListed, totalProcessed, keywordIdx, (int) validKeywords, "完成");
        }

        log.info("[{}] 搜索上架完成，共处理{}条，上架{}条", accountName, totalProcessed, totalListed);
        return reachedLimit;
    }

    private void updateSearchProgress(Long taskId, int progress, int totalSteps, int currentStep, int listed,
                                      int processed, int keywordIdx, int keywordTotal, String detail) {
        if (taskId == null) return;
        JSONObject attrs = new JSONObject();
        attrs.put("progress", progress);     // 步数百分比(保留兼容)
        attrs.put("total", totalSteps);
        attrs.put("current", currentStep);
        attrs.put("listed", listed);         // 已上架数
        attrs.put("processed", processed);   // 已处理(搜索到并判断过)的商品数
        attrs.put("keywordIdx", keywordIdx); // 当前进行到第几个关键词
        attrs.put("keywordTotal", keywordTotal);
        attrs.put("detail", detail);
        taskMapper.updateTaskAttributes(taskId, attrs.toJSONString());
    }

    private void batchCreateListings(List<Pair<String, Integer>> items,
                                     Map<String, Long> variantToTaskItemId,
                                     StockXAccount account) {
        String batchId;
        try {
            batchId = stockXClient.createListingV2(items, account);
        } catch (StockXRateLimitException | TaskCancelledException e) {
            throw e; // 限流冷却耗尽 / 取消：交给 runner 处理
        } catch (RuntimeException e) {
            if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                throw e; // Token 过期：终止任务
            }
            String reason = e.getMessage() != null ? e.getMessage() : "上架失败";
            if (reason.length() > 100) {
                reason = reason.substring(0, 100);
            }
            for (Pair<String, Integer> item : items) {
                Long taskItemId = variantToTaskItemId.get(item.getKey());
                if (taskItemId != null) {
                    updateTaskItemResult(taskItemId, reason);
                }
            }
            log.error("[{}] 批量上架失败, 共{}条, 原因:{}", account.getName(), items.size(), reason);
            return;
        }
        // 提交成功(QUEUED)：先把这批全部标"上架处理中"，再丢给后台线程异步按 variantID 回查回填，
        // 主任务不阻塞、继续往下搜索上架。(不再等 REST 批次完成——GraphQL 的 batchId 在官方 REST 查不到、永远 timeout)
        List<String> variantsSnapshot = new ArrayList<>(items.size());
        Map<String, Long> idSnapshot = new HashMap<>();
        for (Pair<String, Integer> item : items) {
            String variantId = item.getKey();
            Long taskItemId = variantToTaskItemId.get(variantId);
            if (taskItemId != null) {
                variantsSnapshot.add(variantId);
                idSnapshot.put(variantId, taskItemId);
                updateTaskItemResult(taskItemId, "上架处理中");
            }
        }
        if (variantsSnapshot.isEmpty()) {
            return;
        }
        String finalBatchId = batchId;
        listingVerifyExecutor.submit(() -> {
            try {
                verifyCreateBatchAsync(finalBatchId, account, variantsSnapshot, idSnapshot);
            } catch (Exception e) {
                log.error("[{}] 异步上架校验异常, batchId:{}", account.getName(), finalBatchId, e);
            }
        });
    }

    /**
     * 后台异步校验上架结果（按 variantID 查当前挂单，不再按 batchId 过滤）。运行在 listingVerifyExecutor 线程上，不阻塞主任务。
     * <p>已实测：SellerListings(batchId) 是临时视图、异步落地慢且会随时间衰减，按 batchId 会把"还没落地"
     * 误判成失败（曾出现已 ACTIVE 的 listing 被标"上架失败"）；按 variantID 查的是该 variant 的真实挂单。
     * <p>分类：synced/ACTIVE→已上架；node 带 errorCode→上架失败:原因；查询失败或暂未落地→保持"上架处理中"（不误判失败）。
     */
    private void verifyCreateBatchAsync(String batchId, StockXAccount account,
                                        List<String> expectVariants, Map<String, Long> variantToTaskItemId) {
        Map<String, JSONObject> byVariant = new HashMap<>();
        boolean everRead = false;
        for (int attempt = 1; attempt <= CREATE_VERIFY_MAX_ATTEMPTS; attempt++) {
            Map<String, JSONObject> states = stockXClient.verifyListingsByVariantIds(expectVariants, account);
            if (states != null) {
                everRead = true;
                byVariant = states;
            }
            // 全部已落定（已生效 或 带 errorCode 的真失败）即可提前结束等待
            boolean allResolved = everRead;
            for (String v : expectVariants) {
                if (!isListingResolved(byVariant.get(v))) {
                    allResolved = false;
                    break;
                }
            }
            if (allResolved || attempt == CREATE_VERIFY_MAX_ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(CREATE_VERIFY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int ok = 0, fail = 0, pending = 0;
        for (String variantId : expectVariants) {
            Long taskItemId = variantToTaskItemId.get(variantId);
            if (taskItemId == null) {
                continue;
            }
            String r = everRead ? resolveListingResult(byVariant.get(variantId)) : null;
            if (r == null) {
                r = "上架处理中"; // 查询失败 / 暂未落地：不误判失败，保持处理中(后台对账会继续重查)
                pending++;
            } else if ("已上架".equals(r)) {
                ok++;
            } else {
                fail++;
            }
            updateTaskItemResult(taskItemId, r);
        }
        log.info("[{}] 异步上架校验完成{}条, batchId:{}, 已上架:{}, 失败:{}, 处理中:{}",
                account.getName(), expectVariants.size(), batchId, ok, fail, pending);
    }

    /**
     * listing 是否真的在架。只认 status==ACTIVE。
     * <p>不能用 synced==true 判活——已实测 DELETED / INACTIVE / CANCELED 的挂单 synced 同样是 true；
     * synced 只表示"无挂起操作、已落定"，与死活正交。用 synced 判活会把死挂单误判成"已上架"
     * （曾实测：某 variant 零活跃挂单、仅剩历史死挂单，却被判"已上架"的假阳性）。
     */
    private boolean isListingActive(JSONObject node) {
        return node != null && "ACTIVE".equalsIgnoreCase(node.getString("status"));
    }

    /** listing 已落定（已生效 或 带 errorCode 的真失败），可结束等待 */
    private boolean isListingResolved(JSONObject node) {
        if (node == null) {
            return false;
        }
        if (isListingActive(node)) {
            return true;
        }
        JSONObject lastOp = node.getJSONObject("lastOperation");
        return lastOp != null && lastOp.getString("errorCode") != null;
    }

    /**
     * 根据挂单 node 判定终态结果：已上架 / 上架失败:原因。
     * 返回 null = 未落定(node 为空或还在同步、无错误码)，调用方应保持"上架处理中"等待下次重查。
     */
    private String resolveListingResult(JSONObject node) {
        if (node == null) {
            return null;
        }
        if (isListingActive(node)) {
            return "已上架";
        }
        JSONObject lastOp = node.getJSONObject("lastOperation");
        String errCode = lastOp != null ? lastOp.getString("errorCode") : null;
        if (errCode != null) {
            String msg = lastOp.getString("displayMessage");
            return "上架失败:" + (StrUtil.isNotBlank(msg) ? msg : errCode);
        }
        return null;
    }

    /** 对账：只重查 "上架处理中" 超过该时长的条目(给同步内联校验留出时间) */
    private static final long RECONCILE_MIN_AGE_MS = 3 * 60 * 1000;
    /** "上架处理中" 超过该时长仍查不到挂单，判为终态失败，避免永远卡处理中 */
    private static final long RECONCILE_TIMEOUT_MS = 30 * 60 * 1000;
    private static final int RECONCILE_BATCH_LIMIT = 500;

    /**
     * 对账"上架处理中"：按 variantID 重查当前挂单，回填 已上架/上架失败:原因；超过 {@link #RECONCILE_TIMEOUT_MS}
     * 仍查不到挂单则判终态失败，避免永远卡处理中。<b>只读不重发</b>，无限流螺旋风险；
     * 同时补掉"进程重启导致后台异步校验丢失、条目永久卡处理中"的缺口。由 PriceScheduler 定时调用。
     */
    public void reconcilePendingListings() {
        long now = System.currentTimeMillis();
        Date ageCutoff = new Date(now - RECONCILE_MIN_AGE_MS);
        List<TaskItemDO> pending = taskItemMapper.selectList(new QueryWrapper<TaskItemDO>()
                .eq("operate_result", "上架处理中")
                .lt("operate_time", ageCutoff)
                .orderByAsc("operate_time")
                .last("LIMIT " + RECONCILE_BATCH_LIMIT));
        if (pending.isEmpty()) {
            return;
        }
        // taskId -> accountName
        Set<Long> taskIds = pending.stream().map(TaskItemDO::getTaskId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> taskAccount = new HashMap<>();
        if (!taskIds.isEmpty()) {
            for (TaskDO t : taskMapper.selectBatchIds(taskIds)) {
                taskAccount.put(t.getId(), t.getAccountName());
            }
        }
        // 按账号分组(校验读走账号级 1qps 限流)
        Map<String, List<TaskItemDO>> byAccount = new HashMap<>();
        for (TaskItemDO it : pending) {
            String acc = taskAccount.get(it.getTaskId());
            if (acc != null && it.getProductId() != null) {
                byAccount.computeIfAbsent(acc, k -> new ArrayList<>()).add(it);
            }
        }
        int total = 0, done = 0, failed = 0, timeout = 0;
        for (Map.Entry<String, List<TaskItemDO>> e : byAccount.entrySet()) {
            StockXAccount account = StockXConfig.getAccount(e.getKey());
            if (account == null) {
                continue;
            }
            List<TaskItemDO> items = e.getValue();
            for (int i = 0; i < items.size(); i += 100) {
                List<TaskItemDO> chunk = items.subList(i, Math.min(i + 100, items.size()));
                List<String> variantIds = chunk.stream().map(TaskItemDO::getProductId).collect(Collectors.toList());
                Map<String, JSONObject> states = stockXClient.verifyListingsByVariantIds(variantIds, account);
                if (states == null) {
                    continue; // 查询失败，本轮跳过，下轮再试
                }
                for (TaskItemDO it : chunk) {
                    total++;
                    String r = resolveListingResult(states.get(it.getProductId()));
                    if (r != null) {
                        updateTaskItemResult(it.getId(), r);
                        if ("已上架".equals(r)) done++; else failed++;
                    } else if (it.getOperateTime() != null && it.getOperateTime().getTime() < now - RECONCILE_TIMEOUT_MS) {
                        updateTaskItemResult(it.getId(), "上架失败:超时未确认生成挂单");
                        timeout++;
                    }
                    // 否则保持"上架处理中"(不改 operate_time，保留 age 计时)，下轮再查
                }
            }
        }
        if (total > 0) {
            log.info("上架对账完成: 扫描{}, 已上架{}, 失败{}, 超时判失败{}", total, done, failed, timeout);
        }
    }

    /** 压价对账：需要重查的中间态(内联校验10s窗口没确认到的) */
    private static final List<String> PRICE_DOWN_PENDING_RESULTS = List.of("压价未确认", "压价未同步", "压价已提交", "压价价格未生效");

    /**
     * 对账"压价未确认/未同步/已提交"：内联校验只轮询约10s，StockX 的 batchId 视图异步落地慢且会衰减，
     * 大批次里常有条目在窗口内还没进视图(未确认)或还没 synced(未同步)——其实几秒后就压成了，
     * 但这些是终态、没有任何机制回头补确认，会持续堆积。这里按 listingId 重查当前挂单真实状态回填：
     * status==ACTIVE 且 synced → 压价成功；带 errorCode → 压价失败；超过 {@link #RECONCILE_TIMEOUT_MS}
     * 仍确认不到 → 判超时失败，避免永远卡中间态。<b>只读不重发</b>，无限流螺旋；同时补进程重启丢失校验的缺口。
     * 由 PriceScheduler 定时调用。
     */
    public void reconcilePendingPriceDown() {
        long now = System.currentTimeMillis();
        Date ageCutoff = new Date(now - RECONCILE_MIN_AGE_MS);
        List<TaskItemDO> pending = taskItemMapper.selectList(new QueryWrapper<TaskItemDO>()
                .in("operate_result", PRICE_DOWN_PENDING_RESULTS)
                .lt("operate_time", ageCutoff)
                .orderByAsc("operate_time")
                .last("LIMIT " + RECONCILE_BATCH_LIMIT));
        if (pending.isEmpty()) {
            return;
        }
        Set<Long> taskIds = pending.stream().map(TaskItemDO::getTaskId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> taskAccount = new HashMap<>();
        if (!taskIds.isEmpty()) {
            for (TaskDO t : taskMapper.selectBatchIds(taskIds)) {
                taskAccount.put(t.getId(), t.getAccountName());
            }
        }
        Map<String, List<TaskItemDO>> byAccount = new HashMap<>();
        for (TaskItemDO it : pending) {
            String acc = taskAccount.get(it.getTaskId());
            if (acc != null && it.getListingId() != null) {
                byAccount.computeIfAbsent(acc, k -> new ArrayList<>()).add(it);
            }
        }
        int total = 0, done = 0, failed = 0, timeout = 0;
        for (Map.Entry<String, List<TaskItemDO>> e : byAccount.entrySet()) {
            StockXAccount account = StockXConfig.getAccount(e.getKey());
            if (account == null) {
                continue;
            }
            List<TaskItemDO> items = e.getValue();
            for (int i = 0; i < items.size(); i += 100) {
                List<TaskItemDO> chunk = items.subList(i, Math.min(i + 100, items.size()));
                List<String> listingIds = chunk.stream().map(TaskItemDO::getListingId).collect(Collectors.toList());
                Map<String, JSONObject> states = stockXClient.verifyListingsByListingIds(listingIds, account);
                if (states == null) {
                    continue; // 查询失败，本轮跳过，下轮再试
                }
                for (TaskItemDO it : chunk) {
                    total++;
                    JSONObject node = states.get(it.getListingId());
                    Integer tgt = it.getTargetPrice() != null ? it.getTargetPrice().intValue() : null;
                    String r = resolvePriceDownResult(node, tgt);
                    if (r != null) {
                        updateTaskItemResult(it.getId(), r);
                        if (r.startsWith("压价成功")) done++; else failed++;
                    } else if (it.getOperateTime() != null && it.getOperateTime().getTime() < now - RECONCILE_TIMEOUT_MS) {
                        // 超时仍未落定：区分"从没 synced"和"synced 但价没到位"，便于事后排因
                        String reason;
                        if (node != null && Boolean.TRUE.equals(node.getBoolean("synced"))) {
                            reason = "压价失败:超时未生效(实际$" + node.getInteger("amount") + ")";
                        } else {
                            reason = "压价失败:超时未确认同步";
                        }
                        updateTaskItemResult(it.getId(), reason);
                        timeout++;
                    }
                    // 否则保持中间态(不改 operate_time，保留 age 计时)，下轮再查
                }
            }
        }
        if (total > 0) {
            log.info("压价对账完成: 扫描{}, 压价成功{}, 失败{}, 超时判失败{}", total, done, failed, timeout);
        }
    }

    /**
     * 压价终态判定：压价成功 / 压价失败:原因 / null(未落定，调用方保持中间态或按超时处理)。
     * 与上架一致——只认 status==ACTIVE，不用 synced 判活(死挂单 synced 也是 true)。
     * <b>成功必须 amount==target</b>：synced 只代表挂单与市场同步，不代表我们的新报价已应用
     * (在售挂单更新后仍是 synced 但停在旧价)，若只按 synced 判成功，真没生效的会被误判成功。
     * synced 但价没到位时返回 null(继续 pending)，由调用方的超时分支兜底判失败。
     *
     * @param target 本条压价的目标价(来自 TaskItemDO.targetPrice)；为 null 时无从比对，退回按 synced 判成功
     */
    private String resolvePriceDownResult(JSONObject node, Integer target) {
        if (node == null) {
            return null;
        }
        JSONObject lastOp = node.getJSONObject("lastOperation");
        String errCode = lastOp != null ? lastOp.getString("errorCode") : null;
        if (errCode != null) {
            String msg = lastOp.getString("displayMessage");
            return "压价失败:" + (StrUtil.isNotBlank(msg) ? msg : errCode);
        }
        if (!"ACTIVE".equalsIgnoreCase(node.getString("status")) || !Boolean.TRUE.equals(node.getBoolean("synced"))) {
            return null; // 仍在同步/未落定
        }
        Integer amount = node.getInteger("amount");
        if (target == null) {
            // 无目标价可比(如进程重启丢失的"已提交")：退回按 synced 判成功
            return amount != null ? "压价成功($" + amount + ")" : "压价成功";
        }
        // 价已到位判成功并带上最终价；价没到位返回 null 继续 pending，交超时兜底
        return amount != null && amount.equals(target) ? "压价成功($" + amount + ")" : null;
    }

    /** 下架对账：需要重查的中间态 */
    private static final List<String> DELIST_PENDING_RESULTS = List.of("下架未确认");

    /**
     * 对账"下架未确认"：内联校验短窗口内没确认到就留下"未确认"，且是终态无兜底。
     * 这里按 listingId 重查该挂单当前真实状态：status!=ACTIVE 或查不到 → 下架成功；
     * 仍 ACTIVE 且 synced 且超过 {@link #RECONCILE_TIMEOUT_MS} → 判"下架失败:仍在架"；否则下轮再查。
     * <p>按 listingId 查(不用 variantID)，压价链路内下架与 Excel 下架都有 listingId，<b>两条路都能覆盖</b>。
     * <b>只读不重发</b>。由 PriceScheduler 定时调用。
     */
    public void reconcilePendingDelist() {
        long now = System.currentTimeMillis();
        Date ageCutoff = new Date(now - RECONCILE_MIN_AGE_MS);
        List<TaskItemDO> pending = taskItemMapper.selectList(new QueryWrapper<TaskItemDO>()
                .in("operate_result", DELIST_PENDING_RESULTS)
                .lt("operate_time", ageCutoff)
                .orderByAsc("operate_time")
                .last("LIMIT " + RECONCILE_BATCH_LIMIT));
        if (pending.isEmpty()) {
            return;
        }
        Set<Long> taskIds = pending.stream().map(TaskItemDO::getTaskId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> taskAccount = new HashMap<>();
        if (!taskIds.isEmpty()) {
            for (TaskDO t : taskMapper.selectBatchIds(taskIds)) {
                taskAccount.put(t.getId(), t.getAccountName());
            }
        }
        Map<String, List<TaskItemDO>> byAccount = new HashMap<>();
        for (TaskItemDO it : pending) {
            String acc = taskAccount.get(it.getTaskId());
            if (acc != null && it.getListingId() != null) {
                byAccount.computeIfAbsent(acc, k -> new ArrayList<>()).add(it);
            }
        }
        int total = 0, done = 0, failed = 0;
        for (Map.Entry<String, List<TaskItemDO>> e : byAccount.entrySet()) {
            StockXAccount account = StockXConfig.getAccount(e.getKey());
            if (account == null) {
                continue;
            }
            List<TaskItemDO> items = e.getValue();
            for (int i = 0; i < items.size(); i += 100) {
                List<TaskItemDO> chunk = items.subList(i, Math.min(i + 100, items.size()));
                List<String> listingIds = chunk.stream().map(TaskItemDO::getListingId).collect(Collectors.toList());
                Map<String, JSONObject> states = stockXClient.verifyListingsByListingIds(listingIds, account);
                if (states == null) {
                    continue;
                }
                for (TaskItemDO it : chunk) {
                    total++;
                    boolean agedOut = it.getOperateTime() != null && it.getOperateTime().getTime() < now - RECONCILE_TIMEOUT_MS;
                    String r = resolveDelistResult(states.get(it.getListingId()), it.getListingId(), agedOut);
                    if (r != null) {
                        updateTaskItemResult(it.getId(), r);
                        if ("下架成功".equals(r)) done++; else failed++;
                    }
                }
            }
        }
        if (total > 0) {
            log.info("下架对账完成: 扫描{}, 下架成功{}, 失败{}", total, done, failed);
        }
    }

    /**
     * 下架终态判定：下架成功 / 下架失败:仍在架 / null(仍在处理，保持)。
     * 该 listing 已不在活跃挂单中(查不到 / 非ACTIVE) → 下架成功；
     * 仍 ACTIVE 且 synced 且已超时 → 下架失败:仍在架。
     */
    private String resolveDelistResult(JSONObject node, String listingId, boolean agedOut) {
        boolean stillActive = node != null
                && "ACTIVE".equalsIgnoreCase(node.getString("status"))
                && listingId != null && listingId.equals(node.getString("id"));
        if (!stillActive) {
            return "下架成功";
        }
        if (Boolean.TRUE.equals(node.getBoolean("synced")) && agedOut) {
            return "下架失败:仍在架";
        }
        return null; // 仍在处理，下轮再查
    }

    private Long insertTaskItemForAccount(Long taskId, String accountId, String inventoryType, JSONObject item) {
        if (taskId == null) return null;
        int round = TaskSwitch.getExcelRound(accountId, inventoryType);

        TaskItemDO taskItemDO = new TaskItemDO();
        taskItemDO.setTaskId(taskId);
        taskItemDO.setRound(round);
        taskItemDO.setTitle(item.getString("productName"));
        taskItemDO.setBrand(item.getString("brand"));
        taskItemDO.setListingId(item.getString("id"));
        taskItemDO.setProductId(item.getString("variantId"));
        taskItemDO.setStyleId(item.getString("styleId"));
        taskItemDO.setSize(item.getString("size"));
        taskItemDO.setEuSize(item.getString("euSize"));
        Integer amount = item.getInteger("amount");
        taskItemDO.setCurrentPrice(amount != null ? BigDecimal.valueOf(amount) : null);

        Integer lowestPrice = ShoesUtil.resolveStockxLowest(inventoryType,
                item.getInteger("standardLowest"),
                item.getInteger("expressStandardLowest"));
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
