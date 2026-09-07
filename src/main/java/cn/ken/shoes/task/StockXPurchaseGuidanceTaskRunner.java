package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
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
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.config.TaskSwitch;
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

@Slf4j
public class StockXPurchaseGuidanceTaskRunner implements Runnable {

    private final StockXAccount account;
    private final Long taskId;
    private final List<ModelNoSearchExcel> inputRows;
    private final StockXClient stockXClient;
    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final StockXPurchaseGuidanceCalculator calculator = new StockXPurchaseGuidanceCalculator();

    public StockXPurchaseGuidanceTaskRunner(StockXAccount account, Long taskId, List<ModelNoSearchExcel> inputRows,
                                            StockXClient stockXClient, TaskMapper taskMapper,
                                            TaskItemMapper taskItemMapper) {
        this.account = account;
        this.taskId = taskId;
        this.inputRows = inputRows != null ? List.copyOf(inputRows) : List.of();
        this.stockXClient = stockXClient;
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
    }

    @Override
    public void run() {
        long start = System.currentTimeMillis();
        int processed = 0;
        int succeeded = 0;
        Map<String, List<StockXSale>> salesCache = new HashMap<>();
        try {
            Map<String, List<ModelNoSearchExcel>> grouped = new LinkedHashMap<>();
            for (ModelNoSearchExcel row : inputRows) {
                grouped.computeIfAbsent(row.getModelNo().trim().toUpperCase(Locale.ROOT), ignored -> new java.util.ArrayList<>())
                        .add(row);
            }
            for (List<ModelNoSearchExcel> rows : grouped.values()) {
                ensureNotCancelled();
                String modelNo = rows.getFirst().getModelNo().trim();
                List<StockXPriceExcel> candidates = stockXClient.searchExactItemWithPrice(
                        modelNo, "shoes", StrUtil.blankToDefault(account.getCountry(), "US"), account);
                if (candidates == null) throw new IllegalStateException("StockX Token已过期，请更新Token");
                for (ModelNoSearchExcel row : rows) {
                    ensureNotCancelled();
                    StockXPriceExcel matched = findVariant(row, candidates);
                    if (matched == null) {
                        insertFailure(row, "未找到对应货号尺码");
                    } else {
                        taskItemMapper.insert(toTaskItem(row, matched, salesCache));
                        succeeded++;
                    }
                    processed++;
                    publishProgress(processed, succeeded);
                }
            }
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            taskMapper.updateTaskFailReason(taskId, "已处理" + processed + "条，成功" + succeeded + "条");
        } catch (TaskCancelledException e) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
        } catch (Exception e) {
            String reason = StrUtil.blankToDefault(e.getMessage(), "购买价格参考任务异常");
            taskMapper.updateTaskFailed(taskId, reason.substring(0, Math.min(reason.length(), 200)));
            taskMapper.updateTaskCost(taskId, TimeUtil.getCostMin(start));
            log.error("[{}] 购买价格参考任务异常, taskId:{}", account.getName(), taskId, e);
        } finally {
            TaskSwitch.clearSearchListRunState(taskId);
        }
    }

    private StockXPriceExcel findVariant(ModelNoSearchExcel input, List<StockXPriceExcel> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        Map<String, Set<String>> filters = ModelNoSearchSizeFilter.build(List.of(input));
        return candidates.stream().filter(item -> ModelNoSearchSizeFilter.matches(filters,
                        item.getModelNo(), item.getUsmSize(), item.getUswSize(), item.getEuSize()))
                .findFirst().orElse(null);
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
        item.setSize(resolveUsSize(input.getSize(), price));
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

    private void publishProgress(int processed, int succeeded) {
        taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                .fluentPut("processed", processed).fluentPut("total", inputRows.size())
                .fluentPut("succeeded", succeeded).toJSONString());
    }

    private static StockXSaleWindow window(int days, BigDecimal average, BigDecimal median, Integer count) {
        return average == null && median == null && count == null ? null
                : new StockXSaleWindow(days, average, median, count, null, null);
    }

    private static BigDecimal positive(Integer value) {
        return value != null && value > 0 ? BigDecimal.valueOf(value) : null;
    }

    private static String resolveUsSize(String requested, StockXPriceExcel price) {
        if (ModelNoSearchSizeFilter.isWomenSize(requested) && StrUtil.isNotBlank(price.getUswSize())) {
            return price.getUswSize();
        }
        return StrUtil.blankToDefault(price.getUsmSize(), requested);
    }

    private void ensureNotCancelled() {
        if (TaskSwitch.isSearchListCancelled(taskId) || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }
}
