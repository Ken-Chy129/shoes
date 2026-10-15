package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayMarketingApiClient;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayProfitTargetExcel;
import cn.ken.shoes.util.ShoesUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * eBay 盈利改价（含广告费）：与 {@link EbayPriceSyncService} 并列的独立任务，二者互斥。
 *
 * <p>每轮以 eBay 实时价格 x 为准，按 {@link EbayProfitCalculator} 计算利润，与目标盈利 a 比较：
 * <ul>
 *   <li>利润 ≤ a：不盈利，库存置 0（不结束 listing）；</li>
 *   <li>利润 &gt; a：若 x-1 仍盈利则降价 1 美元，否则价格不动；库存为 0 的同时恢复为 1。</li>
 * </ul>
 * 广告费率取运行中按成交付费活动里该商品的实际费率，不在活动里按 0；得物无价按不盈利处理。
 * 只处理港区商品。按 GetSellerList 分页：拉一页、处理一页、写回一页，再拉下一页。
 */
@Slf4j
@Service
public class EbayProfitPriceService {

    public static final String TASK_TYPE = "ebay_profit_price";
    public static final BigDecimal PRICE_STEP = BigDecimal.ONE;
    public static final int RESTORE_QUANTITY = 1;
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.99");
    private static final long MIN_INTERVAL_HOURS = 1;
    private static final long MAX_INTERVAL_HOURS = 168;
    private static final BigDecimal MAX_PROFIT = new BigDecimal("100000");
    private static final int PARALLEL_BATCHES = 4;
    private static final int MAX_OVERRIDE_ROWS = 20000;

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final EbayListingMapper ebayListingMapper;
    private final EbaySellApiClient sellClient;
    private final EbayTradingApiClient tradingClient;
    private final EbayMarketingApiClient marketingClient;
    private final PoisonClient poisonClient;
    private final TaskInputSnapshotStore snapshotStore;
    private final EbayProperties properties;
    private final Consumer<Runnable> threadStarter;
    private final Map<Long, RunHandle> running = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService updateExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public EbayProfitPriceService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                                  EbayListingMapper ebayListingMapper, EbaySellApiClient sellClient,
                                  EbayTradingApiClient tradingClient, EbayMarketingApiClient marketingClient,
                                  PoisonClient poisonClient, TaskInputSnapshotStore snapshotStore,
                                  EbayProperties properties) {
        this(taskMapper, taskItemMapper, ebayListingMapper, sellClient, tradingClient, marketingClient,
                poisonClient, snapshotStore, properties,
                runnable -> Thread.ofVirtual().name("Ebay-Profit-Price").start(runnable));
    }

    EbayProfitPriceService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                           EbayListingMapper ebayListingMapper, EbaySellApiClient sellClient,
                           EbayTradingApiClient tradingClient, EbayMarketingApiClient marketingClient,
                           PoisonClient poisonClient, TaskInputSnapshotStore snapshotStore,
                           EbayProperties properties, Consumer<Runnable> threadStarter) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.ebayListingMapper = ebayListingMapper;
        this.sellClient = sellClient;
        this.tradingClient = tradingClient;
        this.marketingClient = marketingClient;
        this.poisonClient = poisonClient;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.threadStarter = threadStarter;
    }

    /**
     * @param overrides 按货号指定的目标盈利，优先于默认值；可为空
     * @return 任务ID；本任务或「eBay定时改价」正在运行时返回 null
     */
    public synchronized Long start(long intervalHours, BigDecimal defaultProfit,
                                   List<EbayProfitTargetExcel> overrides) {
        validate(intervalHours, defaultProfit);
        Map<String, BigDecimal> targets = normalizeOverrides(overrides);
        if (!canRun()) {
            return null;
        }
        List<EbayProfitTargetExcel> snapshot = new ArrayList<>();
        targets.forEach((styleId, profit) -> {
            EbayProfitTargetExcel row = new EbayProfitTargetExcel();
            row.setStyleId(styleId);
            row.setTargetProfit(profit);
            snapshot.add(row);
        });
        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("intervalHours", intervalHours)
                .fluentPut("defaultProfit", defaultProfit)
                .fluentPut("overrideCount", snapshot.size())
                .fluentPut("marketplaceId", properties.getDefaultMarketplaceId())
                .toJSONString());
        taskMapper.insert(task);
        RunHandle handle = new RunHandle();
        running.put(task.getId(), handle);
        try {
            if (!snapshot.isEmpty()) {
                snapshotStore.saveEbayProfitTargets(task.getId(), snapshot);
            }
            threadStarter.accept(() -> runLoop(task.getId(), intervalHours, defaultProfit, targets, handle));
            return task.getId();
        } catch (RuntimeException e) {
            running.remove(task.getId());
            taskMapper.updateTaskFailed(task.getId(), "任务输入保存或启动失败");
            throw e;
        }
    }

    public synchronized void cancel(Long taskId) {
        RunHandle handle = running.get(taskId);
        if (handle == null) {
            taskMapper.cancelRunningTask(taskId);
            return;
        }
        handle.cancelled.set(true);
        handle.stop.countDown();
    }

    /** 与「eBay定时改价」互斥：两者都改同一批港区商品的价格。 */
    public boolean canRun() {
        return running.isEmpty()
                && taskMapper.selectRunningTask("ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode()) == null
                && taskMapper.selectRunningTask("ebay", EbayPriceSyncService.TASK_TYPE,
                TaskDO.TaskStatusEnum.RUNNING.getCode()) == null;
    }

    private void runLoop(Long taskId, long intervalHours, BigDecimal defaultProfit,
                         Map<String, BigDecimal> targets, RunHandle handle) {
        int round = 0;
        boolean cancelled = false;
        try {
            while (!handle.cancelled.get()) {
                round++;
                try {
                    runSingleRound(taskId, round, defaultProfit, targets, handle.cancelled);
                    taskMapper.updateTaskRound(taskId, round);
                } catch (TaskCancelledException | java.util.concurrent.CancellationException e) {
                    break;
                } catch (Exception e) {
                    if (handle.cancelled.get()) {
                        break;
                    }
                    log.error("eBay盈利改价第{}轮失败, taskId={}", round, taskId, e);
                    recordRoundFailure(taskId, round, e);
                }
                if (handle.stop.await(intervalHours, TimeUnit.HOURS)) {
                    break;
                }
            }
            cancelled = true;
        } catch (InterruptedException e) {
            cancelled = true;
        } catch (Throwable e) {
            log.error("eBay盈利改价线程异常退出, taskId={}", taskId, e);
        } finally {
            running.remove(taskId);
            boolean interrupted = Thread.interrupted();
            persistTerminalStatus(taskId, cancelled || handle.cancelled.get()
                    ? TaskDO.TaskStatusEnum.CANCEL.getCode() : TaskDO.TaskStatusEnum.FAILED.getCode());
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 一轮的决策结果，供测试直接断言。 */
    enum Action { LOWER_PRICE, RESTORE, LOWER_AND_RESTORE, ZERO_STOCK, KEEP, ALREADY_ZERO }

    record Decision(Action action, BigDecimal newPrice, Integer newQuantity, BigDecimal profit,
                    BigDecimal profitAfterStep) {
    }

    /** 纯决策逻辑：给定当前价、可售数量、成本参数，决定本轮动作。 */
    static Decision decide(BigDecimal price, int availableQuantity, BigDecimal poisonPrice, BigDecimal adRate,
                           BigDecimal targetProfit, BigDecimal exchangeRate, BigDecimal freight) {
        if (poisonPrice == null) {
            return availableQuantity > 0
                    ? new Decision(Action.ZERO_STOCK, null, 0, null, null)
                    : new Decision(Action.ALREADY_ZERO, null, null, null, null);
        }
        BigDecimal profit = EbayProfitCalculator.profit(price, adRate, exchangeRate, poisonPrice, freight);
        if (profit.compareTo(targetProfit) <= 0) {
            return availableQuantity > 0
                    ? new Decision(Action.ZERO_STOCK, null, 0, profit, null)
                    : new Decision(Action.ALREADY_ZERO, null, null, profit, null);
        }
        BigDecimal lowered = price.subtract(PRICE_STEP).setScale(2, RoundingMode.HALF_UP);
        BigDecimal loweredProfit = lowered.compareTo(MIN_PRICE) < 0 ? null
                : EbayProfitCalculator.profit(lowered, adRate, exchangeRate, poisonPrice, freight);
        boolean lower = loweredProfit != null && loweredProfit.compareTo(targetProfit) > 0;
        boolean restore = availableQuantity <= 0;
        if (lower && restore) {
            return new Decision(Action.LOWER_AND_RESTORE, lowered, RESTORE_QUANTITY, profit, loweredProfit);
        }
        if (lower) {
            return new Decision(Action.LOWER_PRICE, lowered, null, profit, loweredProfit);
        }
        if (restore) {
            return new Decision(Action.RESTORE, null, RESTORE_QUANTITY, profit, loweredProfit);
        }
        return new Decision(Action.KEEP, null, null, profit, loweredProfit);
    }

    /**
     * 一轮：先读一次广告费率，再逐页读 eBay 在架商品（GetSellerList 每页 200 个 listing），
     * 每读到一页就立即查这一页的得物价、计算并写回 eBay，处理完再读下一页，保证用的是几秒内的实时价格/库存。
     *
     * <p>广告费率读取失败时整轮不改任何商品（按 0 计会低估成本而过度降价）；
     * 某页读取失败则本轮在该页中断，之前已处理的页保留，剩余商品留到下一轮。
     * 翻页期间有上下架会让页边界移动，同一 SKU 可能出现在两页，按 SKU 去重，保证一轮内每个商品最多处理一次。
     */
    void runSingleRound(Long taskId, int round, BigDecimal defaultProfit, Map<String, BigDecimal> targets,
                        AtomicBoolean cancelled) {
        ensureNotCancelled(cancelled);
        BigDecimal exchangeRate = exchangeRate();
        BigDecimal freight = BigDecimal.valueOf(PriceSwitch.FREIGHT == null ? 0 : PriceSwitch.FREIGHT);

        Map<String, EbayListingDO> bySku = new HashMap<>();
        int skippedUsRegion = 0;
        for (EbayListingDO mapping : listOrEmpty(ebayListingMapper.selectActive())) {
            if (mapping.getSku() == null || mapping.getSku().isBlank()) {
                continue;
            }
            if (mapping.getRegion() != null
                    && EbayListingRegion.US.getCode().equalsIgnoreCase(mapping.getRegion().trim())) {
                skippedUsRegion++;
                continue;
            }
            bySku.putIfAbsent(mapping.getSku(), mapping);
        }
        if (bySku.isEmpty()) {
            taskMapper.updateTaskFailReason(taskId, "未找到港区eBay上架映射，本轮无商品可改价");
            return;
        }

        Map<String, BigDecimal> adRates = marketingClient.getRunningAdRates();
        ensureNotCancelled(cancelled);

        Counters counters = new Counters(skippedUsRegion, adRates.size());
        Set<String> processed = new HashSet<>();
        try {
            tradingClient.forEachActiveSkuPage(cancelled::get, page -> {
                ensureNotCancelled(cancelled);
                processPage(taskId, round, page, bySku, processed, adRates, targets, defaultProfit,
                        exchangeRate, freight, counters, cancelled);
                counters.pages = page.pageNumber();
                counters.totalPages = page.totalPages();
                writeAttributes(taskId, counters);
            });
        } catch (TaskCancelledException | java.util.concurrent.CancellationException e) {
            writeAttributes(taskId, counters);
            throw e;
        } catch (RuntimeException e) {
            writeAttributes(taskId, counters);
            throw new RoundInterruptedException("第" + (counters.pages + 1) + "页处理失败，本轮已中断（前"
                    + counters.pages + "页已处理）：" + safeError(e), e);
        }
        counters.notListed = (int) bySku.keySet().stream().filter(sku -> !processed.contains(sku)).count();
        List<String> problems = new ArrayList<>();
        if (counters.total == 0) {
            problems.add("本轮没有可改价的在架港区商品（映射" + bySku.size() + "个）");
        }
        if (counters.failed > 0) {
            problems.add("本轮有 " + counters.failed + " 个商品处理失败");
        }
        if (counters.poisonSkippedPages > 0) {
            problems.add(counters.poisonSkippedPages + " 页得物查价未返回结果，已跳过这些页");
        }
        taskMapper.updateTaskFailReason(taskId, problems.isEmpty() ? null : String.join("；", problems));
        writeAttributes(taskId, counters);
    }

    private void processPage(Long taskId, int round, EbayTradingApiClient.ActiveSkuPage page,
                             Map<String, EbayListingDO> bySku, Set<String> processed,
                             Map<String, BigDecimal> adRates, Map<String, BigDecimal> targets,
                             BigDecimal defaultProfit, BigDecimal exchangeRate, BigDecimal freight,
                             Counters counters, AtomicBoolean cancelled) {
        List<Context> contexts = new ArrayList<>();
        Set<String> modelNos = new HashSet<>();
        for (EbayTradingApiClient.ActiveSku current : page.skus()) {
            EbayListingDO mapping = bySku.get(current.sku());
            // 不在港区映射里的（美区或非本系统上架）不处理；已在前面页处理过的跳过，避免一轮内重复降价。
            if (mapping == null || !processed.add(current.sku())) {
                continue;
            }
            String euSize = firstNonBlank(mapping.getEuSize(), euSizeFrom(mapping.getSize()));
            if (isBlank(mapping.getOfferId()) || isBlank(mapping.getStyleId()) || euSize == null) {
                counters.skipped++;
                continue;
            }
            String styleId = mapping.getStyleId().trim();
            contexts.add(new Context(mapping, current, euSize, styleId,
                    adRates.getOrDefault(current.listingId(), BigDecimal.ZERO),
                    targets.getOrDefault(normalizeStyleId(styleId), defaultProfit)));
            modelNos.add(styleId);
        }
        if (contexts.isEmpty()) {
            return;
        }

        List<PoisonPriceDO> poisonPrices = poisonClient.batchQueryPrice(new ArrayList<>(modelNos));
        ensureNotCancelled(cancelled);
        if (poisonPrices == null || poisonPrices.isEmpty()) {
            // 整页一个价格都没有，多半是查价接口异常，不能据此把整页库存清零。
            counters.poisonSkippedPages++;
            counters.poisonSkipped += contexts.size();
            log.warn("eBay盈利改价第{}页得物查价无结果，跳过该页{}个商品, taskId={}", page.pageNumber(),
                    contexts.size(), taskId);
            return;
        }
        Map<String, BigDecimal> prices = new HashMap<>();
        for (PoisonPriceDO price : poisonPrices) {
            if (price != null && price.getModelNo() != null && price.getEuSize() != null
                    && price.getPrice() != null && price.getPrice() > 0) {
                prices.putIfAbsent(priceKey(price.getModelNo(), price.getEuSize()), BigDecimal.valueOf(price.getPrice()));
            }
        }

        counters.total += contexts.size();
        List<Planned> planned = new ArrayList<>();
        for (Context context : contexts) {
            BigDecimal poisonPrice = prices.get(priceKey(context.styleId(), context.euSize()));
            Decision decision = decide(context.live().price(), context.live().availableQuantity(), poisonPrice,
                    context.adRate(), context.targetProfit(), exchangeRate, freight);
            if (decision.action() == Action.KEEP) {
                counters.kept++;
            } else if (decision.action() == Action.ALREADY_ZERO) {
                counters.alreadyZero++;
            } else {
                planned.add(new Planned(context, poisonPrice, decision));
            }
        }

        List<List<Planned>> batches = partition(planned, EbaySellApiClient.BULK_PRICE_QUANTITY_LIMIT);
        for (int i = 0; i < batches.size(); i += PARALLEL_BATCHES) {
            ensureNotCancelled(cancelled);
            List<List<Planned>> wave = batches.subList(i, Math.min(i + PARALLEL_BATCHES, batches.size()));
            List<Future<List<EbaySellApiClient.OfferUpdateResult>>> futures = new ArrayList<>();
            for (List<Planned> batch : wave) {
                List<EbaySellApiClient.OfferPriceQuantity> updates = batch.stream().map(p ->
                        new EbaySellApiClient.OfferPriceQuantity(p.context().mapping().getSku(),
                                p.context().mapping().getOfferId(), p.decision().newPrice(),
                                properties.getDefaultCurrency(), p.decision().newQuantity())).toList();
                futures.add(updateExecutor.submit(() -> {
                    try {
                        return sellClient.bulkUpdatePriceQuantity(updates);
                    } catch (Exception e) {
                        String error = safeError(e);
                        return updates.stream().map(u -> new EbaySellApiClient.OfferUpdateResult(
                                u.sku(), u.offerId(), 0, error)).toList();
                    }
                }));
            }
            for (int b = 0; b < wave.size(); b++) {
                recordBatch(taskId, round, wave.get(b), await(futures.get(b), wave.get(b)), counters);
            }
        }
    }

    /** 本轮在中途某页失败：之前的页已处理，失败原因里需说明，不能写成「本轮未改价」。 */
    static final class RoundInterruptedException extends RuntimeException {
        RoundInterruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void recordBatch(Long taskId, int round, List<Planned> batch,
                             List<EbaySellApiClient.OfferUpdateResult> results, Counters counters) {
        Map<String, EbaySellApiClient.OfferUpdateResult> byOfferId = new HashMap<>();
        for (EbaySellApiClient.OfferUpdateResult result : results) {
            byOfferId.putIfAbsent(result.offerId(), result);
        }
        for (Planned plan : batch) {
            EbaySellApiClient.OfferUpdateResult result = byOfferId.get(plan.context().mapping().getOfferId());
            Decision decision = plan.decision();
            String detail = detail(plan);
            String operateResult;
            if (result == null || !result.success()) {
                counters.failed++;
                operateResult = "失败(" + (result == null ? "eBay未返回该offer的处理结果" : result.error()) + ") " + detail;
            } else {
                switch (decision.action()) {
                    case LOWER_PRICE -> counters.lowered++;
                    case RESTORE -> counters.restored++;
                    case LOWER_AND_RESTORE -> {
                        counters.lowered++;
                        counters.restored++;
                    }
                    case ZERO_STOCK -> counters.zeroed++;
                    default -> {
                    }
                }
                operateResult = label(decision.action()) + " " + detail;
                rememberSnapshot(plan);
            }
            recordItem(taskId, round, plan, truncate(operateResult, 250));
        }
    }

    private static String label(Action action) {
        return switch (action) {
            case LOWER_PRICE -> "降价";
            case RESTORE -> "恢复库存";
            case LOWER_AND_RESTORE -> "降价并恢复库存";
            case ZERO_STOCK -> "不盈利，库存置0";
            default -> "";
        };
    }

    private static String detail(Planned plan) {
        Decision d = plan.decision();
        StringBuilder text = new StringBuilder("$").append(plan.context().live().price().toPlainString());
        if (d.newPrice() != null) {
            text.append("→$").append(d.newPrice().toPlainString());
        }
        text.append(" 利润");
        text.append(d.profit() == null ? "-(得物无价)" : "¥" + d.profit().toPlainString());
        if (d.newPrice() != null && d.profitAfterStep() != null) {
            text.append("→¥").append(d.profitAfterStep().toPlainString());
        }
        text.append(" 目标¥").append(plan.context().targetProfit().stripTrailingZeros().toPlainString())
                .append(" 广告").append(plan.context().adRate().stripTrailingZeros().toPlainString()).append('%');
        return text.toString();
    }

    private void rememberSnapshot(Planned plan) {
        try {
            Integer quantity = plan.decision().newQuantity() != null ? plan.decision().newQuantity()
                    : Integer.valueOf(plan.context().live().availableQuantity());
            ebayListingMapper.updatePriceAndQuantity(plan.context().mapping().getSku(),
                    plan.decision().newPrice() != null ? plan.decision().newPrice() : plan.context().live().price(),
                    quantity);
        } catch (Exception e) {
            log.warn("eBay映射价格快照更新失败, sku:{}", plan.context().mapping().getSku(), e);
        }
    }

    private void recordItem(Long taskId, int round, Planned plan, String result) {
        EbayListingDO mapping = plan.context().mapping();
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(round);
        item.setTitle(mapping.getTitle());
        item.setListingId(plan.context().live().listingId());
        item.setSku(mapping.getSku());
        item.setOfferId(mapping.getOfferId());
        item.setStyleId(mapping.getStyleId());
        item.setSize(mapping.getSize());
        item.setEuSize(plan.context().euSize());
        item.setCurrentPrice(plan.context().live().price());
        item.setTargetPrice(plan.decision().newPrice());
        item.setListingQuantity(plan.decision().newQuantity() != null ? plan.decision().newQuantity()
                : plan.context().live().availableQuantity());
        item.setPoisonPrice(plan.poisonPrice());
        item.setProfit35(plan.decision().profit());
        item.setOperateResult(result);
        item.setOperateTime(new Date());
        try {
            taskItemMapper.insert(item);
        } catch (Exception e) {
            log.warn("eBay盈利改价明细写入失败, taskId={}, sku={}", taskId, mapping.getSku(), e);
        }
    }

    private void writeAttributes(Long taskId, Counters c) {
        try {
            taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                    .fluentPut("total", c.total)
                    .fluentPut("lowered", c.lowered)
                    .fluentPut("restored", c.restored)
                    .fluentPut("zeroed", c.zeroed)
                    .fluentPut("kept", c.kept)
                    .fluentPut("alreadyZero", c.alreadyZero)
                    .fluentPut("failed", c.failed)
                    .fluentPut("skipped", c.skipped)
                    .fluentPut("skippedUsRegion", c.skippedUsRegion)
                    .fluentPut("notListed", c.notListed)
                    .fluentPut("poisonSkipped", c.poisonSkipped)
                    .fluentPut("adListings", c.adListings)
                    .fluentPut("pages", c.pages)
                    .fluentPut("totalPages", c.totalPages)
                    .toJSONString());
        } catch (Exception e) {
            log.warn("eBay盈利改价进度写入失败, taskId={}", taskId, e);
        }
    }

    private List<EbaySellApiClient.OfferUpdateResult> await(Future<List<EbaySellApiClient.OfferUpdateResult>> future,
                                                            List<Planned> batch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException e) {
                    String error = e.getCause() instanceof Exception cause ? safeError(cause) : "系统异常";
                    return batch.stream().map(p -> new EbaySellApiClient.OfferUpdateResult(
                            p.context().mapping().getSku(), p.context().mapping().getOfferId(), 0, error)).toList();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recordRoundFailure(Long taskId, int round, Exception e) {
        try {
            String reason = e instanceof RoundInterruptedException
                    ? "第" + round + "轮" + e.getMessage()
                    : "第" + round + "轮失败（本轮未改价）：" + safeError(e);
            taskMapper.updateTaskFailReason(taskId, truncate(reason, 500));
        } catch (Exception writeFailure) {
            log.warn("eBay盈利改价失败原因写入失败, taskId={}", taskId, writeFailure);
        }
    }

    private void persistTerminalStatus(Long taskId, String status) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                taskMapper.updateTaskStatus(taskId, status);
                return;
            } catch (Exception e) {
                log.warn("eBay盈利改价终态写入失败(第{}次), taskId={}", attempt, taskId, e);
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ==================== 输入与工具 ====================

    static Map<String, BigDecimal> normalizeOverrides(List<EbayProfitTargetExcel> rows) {
        Map<String, BigDecimal> targets = new LinkedHashMap<>();
        if (rows == null) {
            return targets;
        }
        List<String> errors = new ArrayList<>();
        int rowNumber = 1;
        for (EbayProfitTargetExcel row : rows) {
            rowNumber++;
            if (row == null || (isBlank(row.getStyleId()) && row.getTargetProfit() == null)) {
                continue;
            }
            if (isBlank(row.getStyleId())) {
                errors.add("第" + rowNumber + "行货号为空");
                continue;
            }
            if (row.getTargetProfit() == null || row.getTargetProfit().abs().compareTo(MAX_PROFIT) > 0) {
                errors.add("第" + rowNumber + "行目标盈利无效");
                continue;
            }
            targets.put(normalizeStyleId(row.getStyleId()), row.getTargetProfit().setScale(2, RoundingMode.HALF_UP));
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors.subList(0, Math.min(10, errors.size())))
                    + (errors.size() > 10 ? " 等" + errors.size() + "处错误" : ""));
        }
        if (targets.size() > MAX_OVERRIDE_ROWS) {
            throw new IllegalArgumentException("单独设置的货号最多" + MAX_OVERRIDE_ROWS + "个");
        }
        return targets;
    }

    private void validate(long intervalHours, BigDecimal defaultProfit) {
        if (intervalHours < MIN_INTERVAL_HOURS || intervalHours > MAX_INTERVAL_HOURS) {
            throw new IllegalArgumentException("执行间隔必须是1到168小时");
        }
        if (defaultProfit == null || defaultProfit.abs().compareTo(MAX_PROFIT) > 0) {
            throw new IllegalArgumentException("默认目标盈利必须在-100000到100000元之间");
        }
    }

    private static BigDecimal exchangeRate() {
        double rate = PriceSwitch.EXCHANGE_RATE == null ? 0D : PriceSwitch.EXCHANGE_RATE;
        if (rate <= 0D) {
            throw new IllegalStateException("汇率配置无效");
        }
        return BigDecimal.valueOf(rate);
    }

    static String normalizeStyleId(String styleId) {
        return styleId == null ? "" : styleId.trim().toUpperCase(Locale.ROOT);
    }

    private static String priceKey(String modelNo, String euSize) {
        return modelNo.trim().toUpperCase(Locale.ROOT) + ":" + ShoesUtil.normalizeUnicodeFraction(euSize);
    }

    private static String euSizeFrom(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        return ShoesUtil.getShoesSizeFrom(ShoesUtil.normalizeUnicodeFraction(size).toUpperCase(Locale.ROOT));
    }

    private void ensureNotCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) {
            throw new TaskCancelledException();
        }
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "系统异常";
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    private record Context(EbayListingDO mapping, EbayTradingApiClient.ActiveSku live, String euSize,
                           String styleId, BigDecimal adRate, BigDecimal targetProfit) {
    }

    private record Planned(Context context, BigDecimal poisonPrice, Decision decision) {
    }

    private static final class Counters {
        final int skippedUsRegion;
        final int adListings;
        int total;
        int skipped;
        int notListed;
        int poisonSkipped;
        int poisonSkippedPages;
        int pages;
        int totalPages;
        int lowered;
        int restored;
        int zeroed;
        int kept;
        int alreadyZero;
        int failed;

        Counters(int skippedUsRegion, int adListings) {
            this.skippedUsRegion = skippedUsRegion;
            this.adListings = adListings;
        }
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CountDownLatch stop = new CountDownLatch(1);
    }
}
