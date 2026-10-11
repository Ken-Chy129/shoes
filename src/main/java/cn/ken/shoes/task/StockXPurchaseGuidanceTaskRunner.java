package cn.ken.shoes.task;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.search.ModelNoSearchSizeFilter;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXPurchaseGuidance;
import cn.ken.shoes.model.stockx.StockXPurchaseGuidanceCalculator;
import cn.ken.shoes.model.stockx.StockXSaleWindow;
import cn.ken.shoes.model.stockx.StockXSale;
import cn.ken.shoes.model.stockx.StockXSaleStatistics;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.util.StockXRateLimitGuard;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;

@Slf4j
public class StockXPurchaseGuidanceTaskRunner implements Runnable {

    private final StockXAccount account;
    private final Long taskId;
    private final List<ModelNoSearchExcel> inputRows;
    private final StockXClient stockXClient;
    private final PriceManager priceManager;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final StockXPurchaseGuidanceCalculator calculator = new StockXPurchaseGuidanceCalculator();
    private final KeywordQuery keywordQuery;
    /** 暂停后继续执行时已写入明细的 variantId，关键词模式跳过它们以复用已完成进度。 */
    private Set<String> resumedVariantIds = Set.of();

    /** 关键词搜索模式参数：与「搜索 / 搜索上架」相同，按 关键词 × 排序 × 页码 遍历 StockX 搜索结果。 */
    public record KeywordQuery(List<String> keywords, List<String> sorts, int pageCount, String searchType) {
        public KeywordQuery {
            keywords = keywords != null ? List.copyOf(keywords) : List.of();
            sorts = sorts != null && !sorts.isEmpty() ? List.copyOf(sorts) : List.of("featured");
            pageCount = Math.max(pageCount, 1);
            searchType = StrUtil.blankToDefault(searchType, "shoes");
        }
    }

    public StockXPurchaseGuidanceTaskRunner(StockXAccount account, Long taskId, List<ModelNoSearchExcel> inputRows,
                                            StockXClient stockXClient, TaskMapper taskMapper,
                                            TaskItemMapper taskItemMapper) {
        this(account, taskId, inputRows, stockXClient, null, taskMapper, taskItemMapper);
    }

    public StockXPurchaseGuidanceTaskRunner(StockXAccount account, Long taskId, List<ModelNoSearchExcel> inputRows,
                                            StockXClient stockXClient, PriceManager priceManager,
                                            TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this(account, taskId, inputRows, null, stockXClient, priceManager, taskMapper, taskItemMapper);
    }

    public StockXPurchaseGuidanceTaskRunner(StockXAccount account, Long taskId, KeywordQuery keywordQuery,
                                            StockXClient stockXClient, PriceManager priceManager,
                                            TaskMapper taskMapper, TaskItemMapper taskItemMapper) {
        this(account, taskId, null, keywordQuery, stockXClient, priceManager, taskMapper, taskItemMapper);
    }

    private StockXPurchaseGuidanceTaskRunner(StockXAccount account, Long taskId, List<ModelNoSearchExcel> inputRows,
                                             KeywordQuery keywordQuery, StockXClient stockXClient,
                                             PriceManager priceManager, TaskMapper taskMapper,
                                             TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.inputRows = inputRows != null ? List.copyOf(inputRows) : List.of();
        this.keywordQuery = keywordQuery;
        this.stockXClient = stockXClient;
        this.priceManager = priceManager;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    public StockXPurchaseGuidanceTaskRunner withResumedVariantIds(java.util.Collection<String> variantIds) {
        this.resumedVariantIds = variantIds != null ? Set.copyOf(variantIds) : Set.of();
        return this;
    }

    /** 关键词模式连续单尺码查询失败达到该次数即终止，避免 Token 失效等系统性错误时白白请求全部尺码。 */
    public static final int MAX_CONSECUTIVE_FAILURES = 5;

    @Override
    public void run() {
        long start = System.currentTimeMillis();
        StockXRateLimitGuard.beginTaskContext(account,
                () -> TaskSwitch.isSearchListCancelled(taskId),
                reason -> taskMapper.updateTaskFailReason(taskId, reason),
                () -> taskMapper.updateTaskFailReason(taskId, null));
        try {
            Map<String, List<StockXSale>> salesCache = new HashMap<>();
            String summary = keywordQuery != null ? runKeywordSearch(salesCache) : runExcel(salesCache);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            taskMapper.updateTaskFailReason(taskId, summary);
        } catch (TaskCancelledException e) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (StockXRateLimitException e) {
            log.warn("[{}] 购买价格参考任务因持续限流暂停, taskId:{}, reason:{}", account.getName(), taskId, e.getMessage());
            taskMapper.updateTaskPaused(taskId, e.getMessage());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
            String reason = StrUtil.blankToDefault(e.getMessage(), "购买价格参考任务异常");
            taskMapper.updateTaskFailed(taskId, reason.substring(0, Math.min(reason.length(), 200)));
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            log.error("[{}] 购买价格参考任务异常, taskId:{}", account.getName(), taskId, e);
        } finally {
            StockXRateLimitGuard.endTaskContext();
            TaskSwitch.clearSearchListRunState(taskId);
        }
    }

    private String runExcel(Map<String, List<StockXSale>> salesCache) {
        int processed = 0;
        int succeeded = 0;
        int fallbackVariants = 0;
        Map<String, List<ModelNoSearchExcel>> grouped = new LinkedHashMap<>();
        for (ModelNoSearchExcel row : inputRows) {
            grouped.computeIfAbsent(row.getModelNo().trim().toUpperCase(Locale.ROOT), ignored -> new java.util.ArrayList<>())
                    .add(row);
        }
        preloadPoisonPrices(grouped.values().stream().map(rows -> rows.getFirst().getModelNo().trim()).toList());
        for (List<ModelNoSearchExcel> rows : grouped.values()) {
            ensureNotCancelled();
            String modelNo = rows.getFirst().getModelNo().trim();
            List<StockXPriceExcel> candidates = stockXClient.searchExactItemWithPrice(
                    modelNo, "shoes", StrUtil.blankToDefault(account.getCountry(), "US"), account);
            if (candidates == null) throw new IllegalStateException("StockX Token已过期，请更新Token");
            Set<String> returnedVariantIds = new HashSet<>();
            boolean hasUnmatchedSize = false;
            for (ModelNoSearchExcel row : rows) {
                ensureNotCancelled();
                StockXPriceExcel matched = findVariant(row, candidates);
                if (matched == null) {
                    if (candidates.isEmpty()) {
                        insertFailure(row, "未找到对应货号");
                    } else {
                        hasUnmatchedSize = true;
                    }
                } else {
                    taskItemMapper.insert(toTaskItem(row, matched, salesCache));
                    returnedVariantIds.add(matched.getId());
                    succeeded++;
                }
                processed++;
                publishProgress(processed, succeeded, "货号: " + modelNo);
            }
            // 服饰等尺码体系与输入不一致时不再判失败：返回该货号其余全部尺码及 variantId，供按 variantId 出价。
            if (hasUnmatchedSize) {
                for (StockXPriceExcel candidate : candidates) {
                    ensureNotCancelled();
                    if (StrUtil.isBlank(candidate.getId()) || !returnedVariantIds.add(candidate.getId())) {
                        continue;
                    }
                    TaskItemDO item = toTaskItem(rows.getFirst(), candidate, salesCache);
                    item.setSize(StrUtil.blankToDefault(candidate.getSizeLabel(), displaySize(null, candidate)));
                    item.setOperateResult("尺码未匹配-已返回该货号全部尺码，可按variantId出价；"
                            + StrUtil.blankToDefault(item.getOperateResult(), ""));
                    taskItemMapper.insert(item);
                    fallbackVariants++;
                }
            }
        }
        publishProgress(processed, succeeded, null);
        return "已处理" + processed + "条，成功" + succeeded + "条"
                + (fallbackVariants > 0 ? "，尺码未匹配补充返回" + fallbackVariants + "个尺码" : "");
    }

    private String runKeywordSearch(Map<String, List<StockXSale>> salesCache) {
        String country = StrUtil.blankToDefault(account.getCountry(), "US");
        int totalSteps = keywordQuery.keywords().size() * keywordQuery.sorts().size() * keywordQuery.pageCount();
        int step = 0;
        int succeeded = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        List<String> emptyCombos = new java.util.ArrayList<>();
        Set<String> seenVariantIds = new HashSet<>(resumedVariantIds);
        int resumed = seenVariantIds.size();
        for (String keyword : keywordQuery.keywords()) {
            for (String sort : keywordQuery.sorts()) {
                for (int page = 1; page <= keywordQuery.pageCount(); page++) {
                    ensureNotCancelled();
                    step++;
                    publishKeywordProgress(keyword, sort, page, step, totalSteps, succeeded);
                    Pair<Integer, List<StockXPriceExcel>> result = stockXClient.searchItemWithPrice(
                            keyword, page, sort, keywordQuery.searchType(), country, account);
                    if (result == null) throw new IllegalStateException("StockX Token已过期，请更新Token");
                    List<StockXPriceExcel> items = result.getValue();
                    if (items == null || items.isEmpty()) {
                        // 与搜索上架一致：某页无结果即视为该排序已到底，剩余页数计入进度。
                        // 第一页就为空可能是无结果也可能是请求抖动，记录下来在任务结果中提示。
                        if (page == 1) emptyCombos.add(keyword + "/" + sort);
                        step += keywordQuery.pageCount() - page;
                        break;
                    }
                    preloadPoisonPrices(items.stream().map(StockXPriceExcel::getModelNo).toList());
                    for (StockXPriceExcel item : items) {
                        ensureNotCancelled();
                        if (StrUtil.isBlank(item.getId()) || !seenVariantIds.add(item.getId())) {
                            continue;
                        }
                        try {
                            ModelNoSearchExcel source = new ModelNoSearchExcel();
                            source.setModelNo(item.getModelNo());
                            TaskItemDO taskItem = toTaskItem(source, item, salesCache);
                            taskItem.setSize(StrUtil.blankToDefault(displaySize(null, item), item.getSizeLabel()));
                            taskItemMapper.insert(taskItem);
                            succeeded++;
                            consecutiveFailures = 0;
                        } catch (TaskCancelledException | StockXRateLimitException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            // 单个尺码的成交记录查询失败不应中断整个关键词任务；但连续失败说明是系统性问题，及时终止。
                            log.warn("[{}] 购买价格参考单尺码查询失败, taskId:{}, variantId:{}",
                                    account.getName(), taskId, item.getId(), e);
                            String reason = StrUtil.blankToDefault(e.getMessage(), "查询成交记录失败");
                            insertKeywordFailure(item, reason);
                            failed++;
                            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                throw new IllegalStateException("连续" + consecutiveFailures
                                        + "个尺码查询失败，已终止(成功" + succeeded + "条)：" + reason, e);
                            }
                        }
                    }
                    Integer totalPage = result.getKey();
                    if (totalPage != null && totalPage > 0 && page >= totalPage) {
                        step += keywordQuery.pageCount() - page;
                        break;
                    }
                }
            }
        }
        if (seenVariantIds.isEmpty()) {
            throw new IllegalStateException("StockX搜索未返回任何商品，可能被上游拦截或限流，请稍后重试");
        }
        publishKeywordProgress(null, null, 0, totalSteps, totalSteps, succeeded);
        String summary = "搜索到" + seenVariantIds.size() + "个尺码，成功" + succeeded
                + "条" + (failed > 0 ? "，失败" + failed + "条" : "")
                + (resumed > 0 ? "，暂停前已完成" + resumed + "个" : "");
        if (!emptyCombos.isEmpty()) {
            String combos = String.join("、", emptyCombos);
            summary += "；首页无结果: " + (combos.length() > 80 ? combos.substring(0, 80) + "…" : combos);
        }
        return summary.length() > 200 ? summary.substring(0, 200) : summary;
    }

    /** 与搜索上架一致：按页批量预加载得物价格，避免逐尺码查询。预加载失败不影响主流程。 */
    private void preloadPoisonPrices(List<String> modelNos) {
        if (priceManager == null || modelNos == null || modelNos.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (String modelNo : modelNos) {
            if (StrUtil.isNotBlank(modelNo)) keys.add(modelNo.trim());
        }
        if (keys.isEmpty()) return;
        try {
            priceManager.batchLoadPrices(keys);
        } catch (RuntimeException e) {
            log.warn("[{}] 批量预加载得物价格失败, taskId:{}", account.getName(), taskId, e);
        }
    }

    private void insertKeywordFailure(StockXPriceExcel price, String reason) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);
        item.setProductId(price.getId());
        item.setBrand(price.getBrand());
        item.setTitle(price.getTitle());
        item.setStyleId(price.getModelNo());
        item.setSize(StrUtil.blankToDefault(displaySize(null, price), price.getSizeLabel()));
        item.setEuSize(price.getEuSize());
        item.setCurrencyCode("USD");
        item.setOperateResult("查询失败-" + reason.substring(0, Math.min(reason.length(), 100)));
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    private void publishKeywordProgress(String keyword, String sort, int page, int step, int total, int succeeded) {
        JSONObject attributes = new JSONObject(true)
                .fluentPut("processed", Math.min(step, total)).fluentPut("total", total)
                .fluentPut("succeeded", succeeded);
        if (keyword != null) {
            attributes.put("detail", "关键词: " + keyword + " | 排序: " + sort + " | 页码: " + page
                    + "/" + keywordQuery.pageCount());
        }
        taskMapper.updateTaskAttributes(taskId, attributes.toJSONString());
    }

    private StockXPriceExcel findVariant(ModelNoSearchExcel input, List<StockXPriceExcel> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(input));
        return candidates.stream().filter(item -> ModelNoSearchSizeFilter.matches(filters,
                        item.getModelNo(), item.getUsmSize(), item.getUswSize(), item.getEuSize()))
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(item -> ModelNoSearchSizeFilter.matchesSizeLabel(input.getSize(), item.getSizeLabel()))
                        .findFirst())
                .orElse(null);
    }

    private TaskItemDO toTaskItem(ModelNoSearchExcel input, StockXPriceExcel price,
                                  Map<String, List<StockXSale>> salesCache) {
        List<StockXSale> sales = salesCache.computeIfAbsent(price.getId(),
                variantId -> stockXClient.queryVariantSales(variantId, account));
        Instant now = Instant.now();
        StockXSaleWindow seven = StockXSaleStatistics.window(sales, 7, now);
        StockXSaleWindow thirty = StockXSaleStatistics.window(sales, 30, now);
        StockXSaleWindow ninety = StockXSaleStatistics.window(sales, 90, now);
        if (ninety.salesCount() == 0 && price.getAveragePrice90Days() != null) {
            ninety = window(90, price.getAveragePrice90Days(), price.getMedianPrice90Days(), price.getSalesCount90Days());
        }
        BigDecimal highestBid = positive(price.getPurchasePrice());
        StockXPurchaseGuidance guidance = calculator.calculate(highestBid, seven, thirty, ninety);
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);
        item.setProductId(price.getId());
        item.setBrand(price.getBrand());
        item.setTitle(price.getTitle());
        item.setStyleId(StrUtil.blankToDefault(price.getModelNo(), input.getModelNo()));
        item.setSize(displaySize(input.getSize(), price));
        item.setEuSize(price.getEuSize());
        item.setCurrencyCode("USD");
        StockXSale latest = StockXSaleStatistics.latest(sales);
        if (latest != null) {
            item.setSalePrice(latest.amount());
            item.setSoldOn(Date.from(latest.createdAt()));
        }
        item.setLowestPrice(positive(price.getStandardPrice()));
        item.setFlexLowestPrice(positive(price.getFlexPrice()));
        item.setHighestBidPrice(highestBid);
        item.setPoisonPrice(lookupPoisonPrice(item.getStyleId(), item.getEuSize()));
        item.setAverageSalePrice7d(seven.averagePrice()); item.setMedianSalePrice7d(seven.medianPrice());
        item.setSalesCount7d(seven.salesCount()); item.setAverageSalePrice30d(thirty.averagePrice());
        item.setMedianSalePrice30d(thirty.medianPrice()); item.setSalesCount30d(thirty.salesCount());
        item.setAverageSalePrice90d(ninety.averagePrice()); item.setMedianSalePrice90d(ninety.medianPrice());
        item.setSalesCount90d(ninety.salesCount());
        item.setRecommendedBid(guidance.recommendedBid());
        item.setReferenceSalePrice(guidance.referencePrice());
        item.setReferencePriceLabel(guidance.referenceLabel());
        item.setSalesActivity(guidance.activity());
        item.setSalesTrend(guidance.trend());
        item.setOperateResult(guidance.reason());
        item.setOperateTime(new Date());
        return item;
    }

    /** 得物价格只是参考列，查询失败不能影响 StockX 建议结果。 */
    private BigDecimal lookupPoisonPrice(String styleId, String euSize) {
        if (priceManager == null || StrUtil.isBlank(styleId) || StrUtil.isBlank(euSize)) {
            return null;
        }
        try {
            Integer poisonPrice = priceManager.getPoisonPrice(styleId, euSize);
            return poisonPrice != null ? BigDecimal.valueOf(poisonPrice) : null;
        } catch (RuntimeException e) {
            log.warn("[{}] 查询得物价格失败, styleId:{}, euSize:{}", account.getName(), styleId, euSize, e);
            return null;
        }
    }

    private void insertFailure(ModelNoSearchExcel input, String reason) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);
        item.setStyleId(input.getModelNo());
        item.setSize(input.getSize());
        item.setCurrencyCode("USD");
        item.setOperateResult("查询失败-" + reason);
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    private void publishProgress(int processed, int succeeded, String detail) {
        JSONObject attributes = new JSONObject(true)
                .fluentPut("processed", processed).fluentPut("total", inputRows.size())
                .fluentPut("succeeded", succeeded);
        if (detail != null) attributes.put("detail", detail);
        taskMapper.updateTaskAttributes(taskId, attributes.toJSONString());
    }

    private static StockXSaleWindow window(int days, BigDecimal average, BigDecimal median, Integer count) {
        return average == null && median == null && count == null ? null
                : new StockXSaleWindow(days, average, median, count, null, null);
    }

    private static BigDecimal positive(Integer value) {
        return value != null && value > 0 ? BigDecimal.valueOf(value) : null;
    }

    private static String displaySize(String requested, StockXPriceExcel price) {
        if (ModelNoSearchSizeFilter.isWomenSize(requested) && StrUtil.isNotBlank(price.getUswSize())) {
            return price.getUswSize();
        }
        if (StrUtil.isNotBlank(price.getUsmSize())) {
            return price.getUsmSize();
        }
        return StrUtil.blankToDefault(price.getSizeLabel(), requested);
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isSearchListCancelled(taskId) || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }
}
