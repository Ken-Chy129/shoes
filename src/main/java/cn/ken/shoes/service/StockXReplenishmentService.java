package cn.ken.shoes.service;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXListingCreateItem;
import cn.ken.shoes.task.StockXOrderItemConverter;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class StockXReplenishmentService {

    private static final DateTimeFormatter PARAM_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final StockXClient stockXClient;
    private final PriceManager priceManager;
    private final StockXService stockXService;
    private final StockXReplenishmentTaskRecorder taskRecorder;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public StockXReplenishmentService(StockXClient stockXClient,
                                      PriceManager priceManager,
                                      StockXService stockXService,
                                      StockXReplenishmentTaskRecorder taskRecorder) {
        this(stockXClient, priceManager, stockXService, taskRecorder, Clock.systemUTC());
    }

    StockXReplenishmentService(StockXClient stockXClient,
                               PriceManager priceManager,
                               StockXService stockXService,
                               StockXReplenishmentTaskRecorder taskRecorder,
                               Clock clock) {
        this.stockXClient = stockXClient;
        this.priceManager = priceManager;
        this.stockXService = stockXService;
        this.taskRecorder = taskRecorder;
        this.clock = clock;
    }

    public void replenishAllEnabledAccountsLastHours(int hours, String trigger) {
        if (hours <= 0) {
            throw new IllegalArgumentException("补单时间范围必须大于0小时");
        }
        Instant endTime = clock.instant();
        Instant startTime = endTime.minus(Duration.ofHours(hours));
        if (!running.compareAndSet(false, true)) {
            log.info("StockX补单任务已在运行，跳过本次重复触发");
            return;
        }
        try {
            replenishAccounts(StockXConfig.getAutoReplenishmentAccounts(), startTime, endTime, trigger);
        } finally {
            running.set(false);
        }
    }

    /** 手动触发单账号补单；任务记录同步创建，实际执行使用虚拟线程。 */
    public Long startManualAccount(String accountId, Instant startTime, Instant endTime) {
        validateRange(startTime, endTime);
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null || !account.isEnabled() || !running.compareAndSet(false, true)) {
            return null;
        }
        long startedAt = System.currentTimeMillis();
        Long taskId = null;
        try {
            taskId = createTask(account, startTime, endTime, "manual");
            Long createdTaskId = taskId;
            Thread.startVirtualThread(() -> {
                try {
                    executeAccount(account, startTime, endTime, createdTaskId, startedAt);
                } finally {
                    running.set(false);
                }
            });
            return taskId;
        } catch (RuntimeException e) {
            running.set(false);
            failTask(taskId, e, startedAt);
            throw e;
        }
    }

    void replenishAccounts(List<StockXAccount> accounts, Instant startTime, Instant endTime, String trigger) {
        validateRange(startTime, endTime);
        for (StockXAccount account : accounts) {
            long startedAt = System.currentTimeMillis();
            Long taskId = null;
            try {
                taskId = createTask(account, startTime, endTime, trigger);
                executeAccount(account, startTime, endTime, taskId, startedAt);
            } catch (Exception e) {
                failTask(taskId, e, startedAt);
                log.error("StockX补单[{}]异常，继续处理其他账号: {}", account.getName(), e.getMessage(), e);
            }
        }
    }

    private void executeAccount(StockXAccount account, Instant startTime, Instant endTime,
                                Long taskId, long startedAt) {
        try {
            ReplenishmentSummary summary = replenishSoldItemsForAccount(account, startTime, endTime, taskId);
            taskRecorder.complete(taskId, TimeUtil.getCostMin(startedAt),
                    "扫描" + summary.scanned() + "笔，符合利润" + summary.profitable()
                            + "笔，提交补单" + summary.listingQuantity() + "件，跳过" + summary.skipped() + "笔");
            log.info("StockX补单[{}]完成: scanned={}, profitable={}, quantity={}, skipped={}",
                    account.getName(), summary.scanned(), summary.profitable(),
                    summary.listingQuantity(), summary.skipped());
        } catch (Exception e) {
            failTask(taskId, e, startedAt);
            log.error("StockX补单[{}]执行失败: {}", account.getName(), e.getMessage(), e);
        }
    }

    ReplenishmentSummary replenishSoldItemsForAccount(StockXAccount account, Instant startTime,
                                                       Instant endTime, Long taskId) {
        validateRange(startTime, endTime);
        FetchResult fetched = fetchSoldItems(account, startTime, endTime, taskId);
        List<TaskItemDO> sales = fetched.items();
        Set<String> styleIds = sales.stream()
                .map(TaskItemDO::getStyleId)
                .filter(StrUtil::isNotBlank)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        priceManager.refreshPrices(styleIds);

        int profitable = 0;
        int listingQuantity = 0;
        int skipped = 0;

        for (TaskItemDO item : sales) {
            item.setListingQuantity(1);

            String invalidReason = validateSale(item);
            if (invalidReason != null) {
                item.setOperateResult("跳过-" + invalidReason);
                taskRecorder.record(item);
                skipped++;
                continue;
            }
            if (ShoesContext.isFlawsModel(item.getStyleId(), item.getEuSize())) {
                item.setOperateResult("跳过-禁爬货号");
                taskRecorder.record(item);
                skipped++;
                continue;
            }
            if (ShoesContext.isNotCompareModel(item.getStyleId(), item.getEuSize())) {
                item.setOperateResult("跳过-不比价货号");
                taskRecorder.record(item);
                skipped++;
                continue;
            }

            Integer lowestAsk = item.getLowestPrice() != null
                    ? item.getLowestPrice().setScale(0, RoundingMode.HALF_UP).intValue() : null;
            if (lowestAsk == null || lowestAsk <= 1) {
                item.setOperateResult("跳过-无最低价");
                taskRecorder.record(item);
                skipped++;
                continue;
            }
            int listPrice = lowestAsk - 1;
            item.setTargetPrice(BigDecimal.valueOf(listPrice));
            item.setCurrentPrice(BigDecimal.valueOf(listPrice));

            Integer poisonPrice = priceManager.getPoisonPrice(item.getStyleId(), item.getEuSize());
            if (poisonPrice == null || poisonPrice <= 0) {
                item.setOperateResult("跳过-得物无价");
                taskRecorder.record(item);
                skipped++;
                continue;
            }
            double profit = ShoesUtil.getStockxEarn(poisonPrice, listPrice, account);
            item.setPoisonPrice(BigDecimal.valueOf(poisonPrice));
            item.setPoison35Price(BigDecimal.valueOf(poisonPrice));
            item.setProfit35(BigDecimal.valueOf(profit).setScale(2, RoundingMode.HALF_UP));
            item.setProfitRate35(BigDecimal.valueOf(profit / poisonPrice)
                    .setScale(4, RoundingMode.HALF_UP));
            if (!ShoesUtil.canStockxEarn(poisonPrice, listPrice, account.getMinProfit(), account)) {
                item.setOperateResult("跳过-不盈利");
                taskRecorder.record(item);
                skipped++;
                continue;
            }

            item.setOperateResult("待上架");
            taskRecorder.record(item);
            stockXService.submitTaskListings(taskId,
                    List.of(new StockXListingCreateItem(item.getProductId(), BigDecimal.valueOf(listPrice), 1)),
                    Map.of(item.getProductId(), item.getId()), account);
            profitable++;
            listingQuantity++;
        }

        JSONObject attributes = new JSONObject(true)
                .fluentPut("scanned", sales.size())
                .fluentPut("profitable", profitable)
                .fluentPut("listingQuantity", listingQuantity)
                .fluentPut("skipped", skipped);
        taskRecorder.updateProgress(taskId, fetched.pages(), attributes.toJSONString());
        return new ReplenishmentSummary(sales.size(), profitable, listingQuantity, skipped);
    }

    private FetchResult fetchSoldItems(StockXAccount account, Instant startTime,
                                       Instant endTime, Long taskId) {
        List<TaskItemDO> items = new ArrayList<>();
        Set<String> seenOrders = new HashSet<>();
        int pages = fetchPendingSales(account, startTime, endTime, taskId, items, seenOrders);
        items.sort(Comparator.comparing(TaskItemDO::getSoldOn,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new FetchResult(items, pages);
    }

    private int fetchPendingSales(StockXAccount account, Instant startTime, Instant endTime,
                                  Long taskId, List<TaskItemDO> items, Set<String> seenOrders) {
        int pages = 0;
        String after = null;
        while (true) {
            JSONObject result = stockXClient.queryPendingAsks(after, account);
            JSONArray edges = requireEdges(result, "待处理", pages + 1);
            pages++;
            boolean pageOlderThanStart = addSales(edges, startTime, endTime, taskId, items, seenOrders);
            JSONObject pageInfo = result.getJSONObject("pageInfo");
            boolean hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
            if (!hasNextPage || pageOlderThanStart) {
                return pages;
            }
            String nextCursor = pageInfo.getString("endCursor");
            if (StrUtil.isBlank(nextCursor) || nextCursor.equals(after)) {
                throw new IllegalStateException("待处理订单分页游标无效");
            }
            after = nextCursor;
        }
    }

    private boolean addSales(JSONArray edges, Instant startTime, Instant endTime,
                             Long taskId, List<TaskItemDO> items, Set<String> seenOrders) {
        boolean foundDatedItem = false;
        boolean allDatedItemsOlder = true;
        for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            if (node == null) {
                continue;
            }
            TaskItemDO item = StockXOrderItemConverter.convertPending(taskId, node);
            item.setLowestPrice(ShoesUtil.toStockxPriceColumn(extractLowestAsk(node)));
            Date soldOn = item.getSoldOn();
            if (soldOn == null) {
                continue;
            }
            foundDatedItem = true;
            Instant soldAt = soldOn.toInstant();
            if (!soldAt.isBefore(startTime)) {
                allDatedItemsOlder = false;
            }
            if (soldAt.isBefore(startTime) || !soldAt.isBefore(endTime)) {
                continue;
            }
            String orderKey = StrUtil.isNotBlank(item.getOrderNumber())
                    ? "order:" + item.getOrderNumber()
                    : "listing:" + item.getListingId();
            if (seenOrders.add(orderKey)) {
                items.add(item);
            }
        }
        return foundDatedItem && allDatedItemsOlder;
    }

    private Integer extractLowestAsk(JSONObject node) {
        JSONObject variant = node.getJSONObject("productVariant");
        JSONObject market = variant != null ? variant.getJSONObject("market") : null;
        JSONObject state = market != null ? market.getJSONObject("state") : null;
        JSONObject levels = state != null ? state.getJSONObject("askServiceLevels") : null;
        Integer standardLowest = lowestAmount(levels, "standard");
        Integer expressLowest = lowestAmount(levels, "expressStandard");
        return ShoesUtil.resolveStockxLowest("STANDARD", standardLowest, expressLowest);
    }

    private Integer lowestAmount(JSONObject levels, String serviceLevel) {
        JSONObject level = levels != null ? levels.getJSONObject(serviceLevel) : null;
        JSONObject lowest = level != null ? level.getJSONObject("lowest") : null;
        return lowest != null ? lowest.getInteger("amount") : null;
    }

    private String validateSale(TaskItemDO item) {
        if (StrUtil.isBlank(item.getProductId())) return "缺少variantId";
        if (StrUtil.isBlank(item.getStyleId())) return "缺少货号";
        if (StrUtil.isBlank(item.getEuSize())) return "缺少EU码";
        if (item.getSalePrice() == null || item.getSalePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return "缺少出售价格";
        }
        if (StrUtil.isNotBlank(item.getCurrencyCode()) && !"USD".equals(item.getCurrencyCode())) {
            return "非美元订单";
        }
        return null;
    }

    private JSONArray requireEdges(JSONObject result, String category, int page) {
        if (result == null) {
            throw new IllegalStateException("查询" + category + "订单无响应，第" + page + "页");
        }
        if (result.getBooleanValue("_unauthorized")) {
            throw new IllegalStateException("TOKEN_EXPIRED");
        }
        JSONArray edges = result.getJSONArray("edges");
        if (edges == null) {
            throw new IllegalStateException("查询" + category + "订单响应缺少edges，第" + page + "页");
        }
        return edges;
    }

    private Long createTask(StockXAccount account, Instant startTime, Instant endTime, String trigger) {
        TaskDO task = new TaskDO();
        task.setPlatform("stockx");
        task.setTaskType(TaskTypeEnum.REPLENISHMENT.getCode());
        task.setAccountName(account.getName());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("trigger", trigger)
                .fluentPut("soldStartTime", formatParamTime(startTime))
                .fluentPut("soldEndTime", formatParamTime(endTime))
                .toJSONString());
        return taskRecorder.start(task);
    }

    private String formatParamTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, BUSINESS_ZONE).format(PARAM_TIME_FORMATTER);
    }

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("补单开始时间必须早于结束时间");
        }
    }

    private void failTask(Long taskId, Exception error, long startedAt) {
        if (taskId == null) {
            return;
        }
        String reason = "TOKEN_EXPIRED".equals(error.getMessage())
                ? "Token已过期，请更新Token"
                : StrUtil.blankToDefault(error.getMessage(), "未知异常");
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }
        taskRecorder.fail(taskId, TimeUtil.getCostMin(startedAt), reason);
    }

    private record FetchResult(List<TaskItemDO> items, int pages) {
    }

    public record ReplenishmentSummary(int scanned, int profitable, int listingQuantity, int skipped) {
    }
}
