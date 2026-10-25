package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eBay 持续改价任务。任务本身保持 running，取消后才结束。
 * 改价范围来自 ebay_listing 映射表（上架成功时写入），每一轮在
 * task_item 留一份审计明细。
 */
@Slf4j
@Service
public class EbayPriceSyncService {

    public static final String TASK_TYPE = "ebay_price_sync";
    private static final long MIN_INTERVAL_HOURS = 1;
    private static final long MAX_INTERVAL_HOURS = 168;
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.01");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("100");
    /** 固定加价项（人民币），未传时沿用历史默认值 250。 */
    public static final BigDecimal DEFAULT_ADDITION = new BigDecimal("250");
    private static final BigDecimal MIN_ADDITION = BigDecimal.ZERO;
    private static final BigDecimal MAX_ADDITION = new BigDecimal("100000");
    /** 同时在途的 bulkUpdatePriceQuantity 请求数（每个请求 25 个 offer）。 */
    private static final int PARALLEL_BATCHES = 4;
    /** 每处理多少波刷新一次任务进度，避免页面长时间看不到进展。 */
    private static final int PROGRESS_EVERY_WAVES = 10;

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final EbayListingMapper ebayListingMapper;
    private final EbaySellApiClient ebayClient;
    private final PoisonClient poisonClient;
    private final EbayProperties properties;
    private final Map<Long, RunHandle> running = new ConcurrentHashMap<>();
    private final ExecutorService updateExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final EbayRepricingRecovery recovery;

    @Autowired
    public EbayPriceSyncService(TaskMapper taskMapper,
                                TaskItemMapper taskItemMapper,
                                EbayListingMapper ebayListingMapper,
                                EbaySellApiClient ebayClient,
                                PoisonClient poisonClient,
                                EbayProperties properties) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.ebayListingMapper = ebayListingMapper;
        this.ebayClient = ebayClient;
        this.poisonClient = poisonClient;
        this.properties = properties;
        this.recovery = new EbayRepricingRecovery(ebayClient, ebayListingMapper, properties);
    }

    EbayPriceSyncService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                         EbayListingMapper ebayListingMapper, EbaySellApiClient ebayClient,
                         PoisonClient poisonClient, EbayProperties properties,
                         java.util.function.LongConsumer retrySleeper) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.ebayListingMapper = ebayListingMapper;
        this.ebayClient = ebayClient;
        this.poisonClient = poisonClient;
        this.properties = properties;
        this.recovery = new EbayRepricingRecovery(ebayClient, ebayListingMapper, properties, retrySleeper);
    }

    public synchronized Long start(long intervalHours, BigDecimal priceMultiplier) {
        return start(intervalHours, priceMultiplier, DEFAULT_ADDITION);
    }

    public synchronized Long start(long intervalHours, BigDecimal priceMultiplier, BigDecimal priceAddition) {
        BigDecimal addition = priceAddition == null ? DEFAULT_ADDITION : priceAddition;
        validate(intervalHours, priceMultiplier, addition);
        TaskDO existing = taskMapper.selectRunningTask(
                "ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode());
        if (existing != null || !running.isEmpty() || profitPriceRunning()) {
            return null;
        }

        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("intervalHours", intervalHours)
                .fluentPut("priceMultiplier", priceMultiplier)
                .fluentPut("priceAddition", addition)
                .fluentPut("marketplaceId", properties.getDefaultMarketplaceId())
                .toJSONString());
        taskMapper.insert(task);
        RunHandle handle = new RunHandle();
        Thread thread = Thread.ofVirtual().name("Ebay-Price-Sync-" + task.getId()).unstarted(
                () -> runLoop(task.getId(), intervalHours, priceMultiplier, addition, handle));
        handle.thread = thread;
        running.put(task.getId(), handle);
        thread.start();
        return task.getId();
    }

    public synchronized void cancel(Long taskId) {
        RunHandle handle = running.get(taskId);
        if (handle == null) {
            // 服务重启后运行句柄会丢失，此时直接结束遗留的 running 记录。
            taskMapper.cancelRunningTask(taskId);
            return;
        }
        // 不中断线程：中断会打断在途的 HTTP/JDBC 请求并让连接失效（曾导致线程异常退出、
        // 任务卡在 running）。工作线程在批次之间检查取消标记，间隔等待由 stop 信号唤醒。
        handle.cancelled.set(true);
        handle.stop.countDown();
    }

    public boolean canRun() {
        return running.isEmpty()
                && taskMapper.selectRunningTask(
                "ebay", TASK_TYPE, TaskDO.TaskStatusEnum.RUNNING.getCode()) == null
                && !profitPriceRunning();
    }

    /** 与「eBay盈利改价」互斥：两者都改同一批港区商品的价格。 */
    private boolean profitPriceRunning() {
        return taskMapper.selectRunningTask("ebay", EbayProfitPriceService.TASK_TYPE,
                TaskDO.TaskStatusEnum.RUNNING.getCode()) != null;
    }

    void runSingleRound(Long taskId, BigDecimal priceMultiplier) {
        runSingleRound(taskId, priceMultiplier, DEFAULT_ADDITION, 0, new AtomicBoolean(false));
    }

    void runSingleRound(Long taskId, BigDecimal priceMultiplier, int round) {
        runSingleRound(taskId, priceMultiplier, DEFAULT_ADDITION, round, new AtomicBoolean(false));
    }

    void runSingleRound(Long taskId, BigDecimal priceMultiplier, BigDecimal priceAddition, int round) {
        runSingleRound(taskId, priceMultiplier, priceAddition, round, new AtomicBoolean(false));
    }

    private void runSingleRound(Long taskId, BigDecimal priceMultiplier, BigDecimal priceAddition,
                                int round, AtomicBoolean cancelled) {
        ensureNotCancelled(cancelled);
        List<EbayListingDO> mappings = ebayListingMapper.selectActive();
        /*
         * 直接以 ebay_listing 映射（SKU + offerId + 区域）为改价范围，不再逐个
         * SKU 调 GET /offer：8 万多个 SKU 单线程读取要十几个小时，而改价只需要
         * offerId，价格/库存用 bulkUpdatePriceQuantity 每 25 个一批写入。
         */
        Map<String, EbayListingDO> bySku = new LinkedHashMap<>();
        for (EbayListingDO mapping : mappings == null ? List.<EbayListingDO>of() : mappings) {
            if (mapping.getSku() != null && !mapping.getSku().isBlank()) {
                bySku.putIfAbsent(mapping.getSku(), mapping);
            }
        }

        List<OfferContext> contexts = new ArrayList<>();
        Set<String> modelNos = new HashSet<>();
        int skipped = 0;
        int skippedUsRegion = 0;
        for (EbayListingDO mapping : bySku.values()) {
            ensureNotCancelled(cancelled);
            if (isUsRegion(mapping)) {
                // 改价只覆盖港区商品；美区的价格和库存由人工/美区上架维护。
                skippedUsRegion++;
                continue;
            }
            if (isBlank(mapping.getOfferId()) || isBlank(mapping.getStyleId())) {
                skipped++;
                continue;
            }
            String euSize = firstNonBlank(mapping.getEuSize(), euSizeFrom(mapping.getSize()));
            if (euSize == null) {
                skipped++;
                continue;
            }
            contexts.add(new OfferContext(mapping, euSize));
            modelNos.add(mapping.getStyleId().trim());
        }

        if (contexts.isEmpty()) {
            // 空轮次不能静默成功，否则映射丢失时页面只会显示"第N轮"而没有任何原因。
            String reason = bySku.isEmpty()
                    ? "未找到eBay上架映射（ebay_listing为空），本轮无商品可改价"
                    : "本轮没有可改价的港区商品（映射" + bySku.size() + "个，跳过" + skipped
                    + "个，美区不改价" + skippedUsRegion + "个）";
            taskMapper.updateTaskFailReason(taskId, reason);
            taskMapper.updateTaskAttributes(taskId, attributes(0, 0, 0, skipped)
                    .fluentPut("skippedUsRegion", skippedUsRegion).toJSONString());
            return;
        }

        /*
         * An empty result for a non-empty batch is deliberately treated as a
         * technical/ambiguous response. This prevents a transient upstream
         * failure from setting every active eBay offer to zero inventory.
         * Individual missing model/size entries are still confirmed no-price
         * results when the batch contains other valid prices.
         */
        List<PoisonPriceDO> poisonPrices = poisonClient.batchQueryPrice(new ArrayList<>(modelNos));
        ensureNotCancelled(cancelled);
        if (poisonPrices == null || poisonPrices.isEmpty()) {
            taskMapper.updateTaskFailReason(taskId, "本轮得物查价未返回可确认结果，已跳过改价和清库存");
            taskMapper.updateTaskAttributes(taskId, attributes(contexts.size(), 0, 0, skipped)
                    .fluentPut("skippedUsRegion", skippedUsRegion)
                    .fluentPut("skippedForPoisonFailure", contexts.size()).toJSONString());
            return;
        }

        Map<String, BigDecimal> prices = new HashMap<>();
        for (PoisonPriceDO price : poisonPrices) {
            if (price == null || price.getModelNo() == null || price.getEuSize() == null
                    || price.getPrice() == null || price.getPrice() <= 0) {
                continue;
            }
            prices.putIfAbsent(priceKey(price.getModelNo(), price.getEuSize()),
                    BigDecimal.valueOf(price.getPrice()));
        }

        List<PlannedUpdate> planned = new ArrayList<>(contexts.size());
        for (OfferContext context : contexts) {
            BigDecimal poisonPrice = prices.get(priceKey(context.mapping().getStyleId(), context.euSize()));
            BigDecimal target = poisonPrice == null ? null
                    : targetPrice(poisonPrice, priceMultiplier, priceAddition);
            /*
             * 有价只改价格、不碰库存：eBay 上的库存会随成交减少，本地快照不可靠，
             * 回写快照会把已卖出的数量加回去。无价则把库存置 0。
             */
            EbaySellApiClient.OfferPriceQuantity update = new EbaySellApiClient.OfferPriceQuantity(
                    context.mapping().getSku(), context.offerId(), target,
                    properties.getDefaultCurrency(), target == null ? 0 : null);
            planned.add(new PlannedUpdate(context, poisonPrice, target, update));
        }

        int[] counters = new int[6]; // changed, noPrice, failed, groupWithdrawn, removedByEbay, detachedOutOfStock
        EbayRepricingRecovery.Round recoveryRound = recovery.newRound();
        List<List<PlannedUpdate>> batches = partition(planned, EbaySellApiClient.BULK_PRICE_QUANTITY_LIMIT);
        for (int i = 0; i < batches.size(); i += PARALLEL_BATCHES) {
            ensureNotCancelled(cancelled);
            List<List<PlannedUpdate>> wave = batches.subList(i, Math.min(i + PARALLEL_BATCHES, batches.size()));
            List<List<EbayRepricingRecovery.Resolution>> results = submitWave(wave, recoveryRound);
            for (int b = 0; b < wave.size(); b++) {
                recordBatch(taskId, round, wave.get(b), results.get(b), counters);
            }
            if ((i / PARALLEL_BATCHES) % PROGRESS_EVERY_WAVES == 0) {
                taskMapper.updateTaskAttributes(taskId, withRecovery(
                        attributes(contexts.size(), counters[0], counters[1], skipped), counters)
                        .fluentPut("skippedUsRegion", skippedUsRegion)
                        .fluentPut("failed", counters[2])
                        .fluentPut("processed", Math.min((i + wave.size()) * EbaySellApiClient.BULK_PRICE_QUANTITY_LIMIT,
                                planned.size()))
                        .toJSONString());
            }
        }
        int failed = counters[2];
        taskMapper.updateTaskFailReason(taskId, failed == 0 ? null : "本轮有 " + failed + " 个商品处理失败");
        taskMapper.updateTaskAttributes(taskId, withRecovery(
                attributes(contexts.size(), counters[0], counters[1], skipped), counters)
                .fluentPut("skippedUsRegion", skippedUsRegion)
                .fluentPut("failed", failed).toJSONString());
    }

    private static JSONObject withRecovery(JSONObject attributes, int[] counters) {
        if (counters[3] > 0) {
            attributes.put("groupWithdrawn", counters[3]);
        }
        if (counters[4] > 0) {
            attributes.put("removedByEbay", counters[4]);
        }
        if (counters[5] > 0) {
            attributes.put("detachedOutOfStock", counters[5]);
        }
        return attributes;
    }

    /**
     * 并发发出一波批量改价请求并等待全部返回。整批请求失败只记为该批商品失败，
     * 不影响同一波的其它批次；等待期间不响应中断，保证在途写入有确定结果。
     */
    private List<List<EbayRepricingRecovery.Resolution>> submitWave(List<List<PlannedUpdate>> wave,
                                                                     EbayRepricingRecovery.Round recoveryRound) {
        List<Future<List<EbayRepricingRecovery.Resolution>>> futures = new ArrayList<>(wave.size());
        for (List<PlannedUpdate> batch : wave) {
            List<EbayRepricingRecovery.Target> targets = batch.stream().map(p -> new EbayRepricingRecovery.Target(
                    p.update(), p.context().mapping().getStyleId(), p.context().mapping().getRegion(),
                    p.context().mapping().getQuantity())).toList();
            futures.add(updateExecutor.submit(() -> recoveryRound.apply(targets)));
        }
        List<List<EbayRepricingRecovery.Resolution>> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            results.add(awaitUninterruptibly(futures.get(i), wave.get(i)));
        }
        return results;
    }

    private List<EbayRepricingRecovery.Resolution> awaitUninterruptibly(
            Future<List<EbayRepricingRecovery.Resolution>> future, List<PlannedUpdate> batch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    String error = e.getCause() instanceof Exception cause ? safeError(cause) : "系统异常";
                    return batch.stream().map(p -> new EbayRepricingRecovery.Resolution(
                            p.update().offerId(), EbayRepricingRecovery.Kind.FAILED, error, null)).toList();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recordBatch(Long taskId, int round, List<PlannedUpdate> batch,
                             List<EbayRepricingRecovery.Resolution> results, int[] counters) {
        Map<String, EbayRepricingRecovery.Resolution> byOfferId = new HashMap<>();
        for (EbayRepricingRecovery.Resolution result : results) {
            if (result != null) {
                byOfferId.putIfAbsent(result.offerId(), result);
            }
        }
        for (PlannedUpdate plan : batch) {
            EbayRepricingRecovery.Resolution result = byOfferId.get(plan.context().offerId());
            String operateResult;
            Integer recordedQuantity = plan.target() == null ? Integer.valueOf(0) : plan.context().mapping().getQuantity();
            if (result == null || result.failed()) {
                counters[2]++;
                operateResult = "改价失败(" + (result == null ? "eBay未返回该offer的处理结果" : result.error()) + ")";
            } else if (result.kind() == EbayRepricingRecovery.Kind.GROUP_WITHDRAWN) {
                counters[3]++;
                operateResult = result.note();
            } else if (result.kind() == EbayRepricingRecovery.Kind.REMOVED_BY_EBAY) {
                counters[4]++;
                operateResult = result.note();
            } else if (result.kind() == EbayRepricingRecovery.Kind.DETACHED_OUT_OF_STOCK) {
                counters[5]++;
                operateResult = result.note();
            } else if (plan.target() != null) {
                counters[0]++;
                rememberPriceAndQuantity(plan.context(), plan.target(), null);
                operateResult = "改价成功($" + plan.target().toPlainString() + ")" + suffix(result);
            } else {
                counters[1]++;
                rememberPriceAndQuantity(plan.context(), null, 0);
                operateResult = "无得物价格，库存置0" + suffix(result);
            }
            recordItem(taskId, round, plan.context(), plan.poisonPrice(), plan.target(),
                    recordedQuantity, truncate(operateResult, 250));
        }
    }

    private static String suffix(EbayRepricingRecovery.Resolution result) {
        return result.note() == null ? "" : "；" + result.note();
    }

    /** 区域以上架时写入映射的 region 为准；历史数据为空视为港区。 */
    private boolean isUsRegion(EbayListingDO mapping) {
        return mapping.getRegion() != null
                && EbayListingRegion.US.getCode().equalsIgnoreCase(mapping.getRegion().trim());
    }

    private void runLoop(Long taskId, long intervalHours, BigDecimal multiplier,
                         BigDecimal addition, RunHandle handle) {
        int round = 0;
        boolean cancelled = false;
        try {
            while (!handle.cancelled.get()) {
                round++;
                try {
                    runSingleRound(taskId, multiplier, addition, round, handle.cancelled);
                    taskMapper.updateTaskRound(taskId, round);
                } catch (TaskCancelledException e) {
                    break;
                } catch (Exception e) {
                    if (handle.cancelled.get()) {
                        // 取消时在途请求抛出的异常属于取消，不能记成本轮失败。
                        break;
                    }
                    log.error("eBay定时改价第{}轮失败，保留现有库存和价格, taskId={}", round, taskId, e);
                    recordRoundFailure(taskId, round, e);
                }
                if (handle.stop.await(intervalHours, TimeUnit.HOURS)) {
                    break;
                }
            }
            cancelled = true;
        } catch (InterruptedException e) {
            // 只有 JVM 关闭等外部原因会中断该线程；取消走 stop 信号，不再中断在途请求。
            cancelled = true;
        } catch (Throwable e) {
            log.error("eBay定时改价线程异常退出, taskId={}", taskId, e);
        } finally {
            running.remove(taskId);
            // 清掉中断标记，否则终态写库会因为 JDBC 套接字被中断而失败，任务一直停在 running。
            boolean interrupted = Thread.interrupted();
            String status = cancelled || handle.cancelled.get()
                    ? TaskDO.TaskStatusEnum.CANCEL.getCode() : TaskDO.TaskStatusEnum.FAILED.getCode();
            persistTerminalStatus(taskId, status);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recordRoundFailure(Long taskId, int round, Exception e) {
        try {
            taskMapper.updateTaskFailReason(taskId, "第" + round + "轮失败：" + safeError(e));
            taskMapper.updateTaskAttributes(taskId, attributes(0, 0, 0, 0)
                    .fluentPut("roundFailed", true).toJSONString());
        } catch (Exception writeFailure) {
            // 记录失败原因不能让改价线程退出，下一轮会继续执行。
            log.warn("eBay定时改价失败原因写入失败, taskId={}", taskId, writeFailure);
        }
    }

    private void persistTerminalStatus(Long taskId, String status) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                taskMapper.updateTaskStatus(taskId, status);
                return;
            } catch (Exception e) {
                log.warn("eBay定时改价终态写入失败(第{}次), taskId={}, status={}", attempt, taskId, status, e);
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void ensureNotCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) {
            throw new TaskCancelledException();
        }
    }

    /** 映射表里的价格/库存只是展示用快照，写失败不影响本次改价结果。 */
    private void rememberPriceAndQuantity(OfferContext context, BigDecimal price, Integer quantity) {
        try {
            ebayListingMapper.updatePriceAndQuantity(context.mapping().getSku(), price, quantity);
        } catch (Exception e) {
            log.warn("eBay映射价格快照更新失败, sku:{}", context.mapping().getSku(), e);
        }
    }

    private void recordItem(Long taskId, int round, OfferContext context, BigDecimal poisonPrice,
                            BigDecimal target, Integer quantity, String result) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(round);
        item.setTitle(context.mapping().getTitle());
        item.setListingId(context.mapping().getListingId());
        item.setSku(context.mapping().getSku());
        item.setOfferId(context.offerId());
        item.setStyleId(context.mapping().getStyleId());
        item.setSize(context.mapping().getSize());
        item.setEuSize(context.euSize());
        item.setCurrentPrice(context.mapping().getPrice());
        item.setTargetPrice(target);
        item.setListingQuantity(quantity);
        item.setPoisonPrice(poisonPrice);
        item.setOperateResult(result);
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    /** 目标美元价 = (得物人民币价 × 系数 + 固定加价) ÷ 汇率。 */
    private BigDecimal targetPrice(BigDecimal poisonPrice, BigDecimal multiplier, BigDecimal addition) {
        double exchangeRate = PriceSwitch.EXCHANGE_RATE == null ? 0D : PriceSwitch.EXCHANGE_RATE;
        if (exchangeRate <= 0D) {
            throw new IllegalStateException("汇率配置无效");
        }
        BigDecimal target = poisonPrice.multiply(multiplier)
                .add(addition == null ? DEFAULT_ADDITION : addition)
                .divide(BigDecimal.valueOf(exchangeRate), 2, RoundingMode.HALF_UP);
        return target.max(new BigDecimal("0.01"));
    }

    private JSONObject attributes(int total, int changed, int noPrice, int skipped) {
        return new JSONObject(true)
                .fluentPut("total", total)
                .fluentPut("changed", changed)
                .fluentPut("noPrice", noPrice)
                .fluentPut("skipped", skipped);
    }

    private String euSizeFrom(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        return ShoesUtil.getShoesSizeFrom(
                ShoesUtil.normalizeUnicodeFraction(size).toUpperCase(Locale.ROOT));
    }

    private String priceKey(String modelNo, String euSize) {
        return modelNo.trim().toUpperCase(Locale.ROOT) + ":" + ShoesUtil.normalizeUnicodeFraction(euSize);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "系统异常";
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    private void validate(long intervalHours, BigDecimal multiplier, BigDecimal addition) {
        if (intervalHours < MIN_INTERVAL_HOURS || intervalHours > MAX_INTERVAL_HOURS) {
            throw new IllegalArgumentException("执行间隔必须是1到168小时");
        }
        if (multiplier == null || multiplier.compareTo(MIN_MULTIPLIER) < 0
                || multiplier.compareTo(MAX_MULTIPLIER) > 0) {
            throw new IllegalArgumentException("得物价格系数必须在0.01到100之间");
        }
        if (addition == null || addition.compareTo(MIN_ADDITION) < 0
                || addition.compareTo(MAX_ADDITION) > 0) {
            throw new IllegalArgumentException("固定加价必须在0到100000元之间");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return batches;
    }

    private record OfferContext(EbayListingDO mapping, String euSize) {
        String offerId() {
            return mapping.getOfferId();
        }
    }

    private record PlannedUpdate(OfferContext context, BigDecimal poisonPrice, BigDecimal target,
                                 EbaySellApiClient.OfferPriceQuantity update) {
    }

    private static final class RunHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** 取消信号：唤醒轮次间隔等待，替代线程中断。 */
        private final CountDownLatch stop = new CountDownLatch(1);
        private volatile Thread thread;

        private RunHandle() {}
    }
}
