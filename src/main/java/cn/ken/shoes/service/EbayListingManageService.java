package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayAnalyticsApiClient;
import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.client.EbayTradingApiClient.ListingVariation;
import cn.ken.shoes.client.EbayTradingApiClient.SellerListing;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayItemIdExcel;
import cn.ken.shoes.model.excel.EbaySetPriceExcel;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * eBay 刊登管理的四个任务，全部以 eBay 物品编号（刊登号）为准，不管刊登是不是本系统发布的：
 * <ul>
 *   <li>获取刊登：列出在售 / 已结束的刊登，带品牌、货号、在售尺码、数量、剩余时间、浏览、收藏、售出、价格区间。</li>
 *   <li>按编号下架：结束指定刊登。</li>
 *   <li>按编号重新上架：把已结束的刊登重新上架（会生成新编号）。</li>
 *   <li>指定尺码改价：把指定刊登的指定尺码改成指定价格。</li>
 * </ul>
 *
 * <p>系统自己发布的刊登，eBay 上同时存在一份 offer 记录。只改刊登不改 offer 会让两边价格对不上，
 * 之后任何按 offer 的操作都可能把价格改回去，所以系统刊登走 Inventory API，其余走 Trading API。
 */
@Slf4j
@Service
public class EbayListingManageService {

    public static final String FETCH_TASK_TYPE = "ebay_fetch_listings";
    public static final String END_TASK_TYPE = "ebay_end_listings";
    public static final String RELIST_TASK_TYPE = "ebay_relist_listings";
    public static final String SET_PRICE_TASK_TYPE = "ebay_set_price";

    public static final String SCOPE_ACTIVE = "active";
    public static final String SCOPE_ENDED = "ended";
    public static final String SCOPE_ALL = "all";
    /** 已结束的刊登最多回溯的天数：eBay 单次查询窗口不超过 120 天。 */
    public static final int ENDED_LOOKBACK_DAYS = 119;
    public static final int MAX_INPUT_ROWS = 5000;
    private static final int MAX_PAGES = 500;
    private static final String REPORT_FILE = "ebay-listings.json";
    /** 报告是 record 列表，fastjson 1.x 不支持 record，用 Jackson 读写。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper REPORT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Pattern ITEM_ID = Pattern.compile("^\\d{9,19}$");
    /** 链接里的物品编号：/itm/407333465699 或 /itm/标题/407333465699。 */
    private static final Pattern ITEM_URL = Pattern.compile("/itm/(?:[^/?#]+/)?(\\d{9,19})");
    /** 标题末尾的货号（系统标题格式：… 货号）。 */
    private static final Pattern TITLE_STYLE = Pattern.compile("([A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+|[A-Z]{1,4}\\d{3,}[A-Z0-9]*)\\s*$");

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final EbayListingMapper listingMapper;
    private final EbayTradingApiClient tradingClient;
    private final EbaySellApiClient sellClient;
    private final EbayAnalyticsApiClient analyticsClient;
    private final TaskInputSnapshotStore snapshotStore;
    private final EbayProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final Path reportRoot;
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Set<String> runningTypes = ConcurrentHashMap.newKeySet();

    @Autowired
    public EbayListingManageService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                                    EbayListingMapper listingMapper, EbayTradingApiClient tradingClient,
                                    EbaySellApiClient sellClient, EbayAnalyticsApiClient analyticsClient,
                                    TaskInputSnapshotStore snapshotStore, EbayProperties properties) {
        this(taskMapper, taskItemMapper, listingMapper, tradingClient, sellClient, analyticsClient, snapshotStore,
                properties, command -> Thread.ofVirtual().name("Ebay-Listing-Manage").start(command),
                Clock.systemUTC(), Paths.get("files/task-input"));
    }

    EbayListingManageService(TaskMapper taskMapper, TaskItemMapper taskItemMapper, EbayListingMapper listingMapper,
                             EbayTradingApiClient tradingClient, EbaySellApiClient sellClient,
                             EbayAnalyticsApiClient analyticsClient, TaskInputSnapshotStore snapshotStore,
                             EbayProperties properties, Executor executor, Clock clock, Path reportRoot) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.listingMapper = listingMapper;
        this.tradingClient = tradingClient;
        this.sellClient = sellClient;
        this.analyticsClient = analyticsClient;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
        this.reportRoot = reportRoot.toAbsolutePath().normalize();
    }

    // ==================== 任务启动 ====================

    /**
     * 获取刊登。scope：active（在售，默认）/ ended（最近 119 天内结束的）/ all。
     * viewDays：浏览人数统计最近几天（1-90，默认 30）。
     *
     * @return 任务ID；已有同类任务在运行时返回 null
     */
    public synchronized Long startFetch(String scope, Integer viewDays) {
        String normalizedScope = normalizeScope(scope);
        int days = viewDays == null ? 30 : viewDays;
        if (days < 1 || days > EbayAnalyticsApiClient.MAX_DAYS) {
            throw new IllegalArgumentException("浏览人数统计天数需在1到" + EbayAnalyticsApiClient.MAX_DAYS + "之间");
        }
        JSONObject params = new JSONObject(true).fluentPut("scope", normalizedScope).fluentPut("viewDays", days);
        return launch(FETCH_TASK_TYPE, params, null, (taskId, cancelled) -> runFetch(taskId, normalizedScope, days,
                cancelled));
    }

    /** 按编号下架。 */
    public synchronized Long startEnd(List<EbayItemIdExcel> rows) {
        List<String> itemIds = normalizeItemIds(rows);
        return launch(END_TASK_TYPE, new JSONObject(true).fluentPut("inputCount", itemIds.size()),
                taskId -> snapshotStore.saveEbayItemIdInput(taskId, toRows(itemIds)),
                (taskId, cancelled) -> runEnd(taskId, itemIds, cancelled));
    }

    /** 按编号重新上架。 */
    public synchronized Long startRelist(List<EbayItemIdExcel> rows) {
        List<String> itemIds = normalizeItemIds(rows);
        return launch(RELIST_TASK_TYPE, new JSONObject(true).fluentPut("inputCount", itemIds.size()),
                taskId -> snapshotStore.saveEbayItemIdInput(taskId, toRows(itemIds)),
                (taskId, cancelled) -> runRelist(taskId, itemIds, cancelled));
    }

    /** 指定刊登的指定尺码改成指定价格。 */
    public synchronized Long startSetPrice(List<EbaySetPriceExcel> rows) {
        List<PriceTarget> targets = normalizePriceTargets(rows);
        List<EbaySetPriceExcel> snapshot = targets.stream().map(PriceTarget::toExcel).toList();
        return launch(SET_PRICE_TASK_TYPE, new JSONObject(true).fluentPut("inputCount", targets.size()),
                taskId -> snapshotStore.saveEbaySetPriceInput(taskId, snapshot),
                (taskId, cancelled) -> runSetPrice(taskId, targets, cancelled));
    }

    public void cancel(Long taskId) {
        AtomicBoolean cancelled = running.get(taskId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    private interface Body {
        void run(Long taskId, AtomicBoolean cancelled);
    }

    private interface Snapshot {
        void save(Long taskId);
    }

    private Long launch(String taskType, JSONObject params, Snapshot snapshot, Body body) {
        if (taskMapper.selectRunningTask("ebay", taskType, TaskDO.TaskStatusEnum.RUNNING.getCode()) != null
                || runningTypes.contains(taskType)) {
            return null;
        }
        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(taskType);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(params.toJSONString());
        taskMapper.insert(task);
        Long taskId = task.getId();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(taskId, cancelled);
        runningTypes.add(taskType);
        try {
            if (snapshot != null) {
                snapshot.save(taskId);
            }
            executor.execute(() -> {
                try {
                    body.run(taskId, cancelled);
                } catch (Exception e) {
                    log.error("eBay刊登管理任务异常, taskId:{}, type:{}", taskId, taskType, e);
                    taskMapper.updateTaskFailed(taskId, "任务异常：" + safeError(e));
                } finally {
                    running.remove(taskId);
                    runningTypes.remove(taskType);
                }
            });
            return taskId;
        } catch (RuntimeException e) {
            running.remove(taskId);
            runningTypes.remove(taskType);
            taskMapper.updateTaskFailed(taskId, "任务输入保存或启动失败");
            throw e;
        }
    }

    // ==================== 获取刊登 ====================

    /** 导出用的一行刊登概要（同时存成任务快照）。 */
    public record ListingRow(String itemId, String status, String brand, String styleId, String title,
                             String sizes, int sizeCount, int quantity, int sold, BigDecimal minPrice,
                             BigDecimal maxPrice, String timeLeft, String endTime, Integer views, int watchers,
                             String startTime, String sku, String source, String relistParentId,
                             String relistedItemId, String category, String url,
                             List<SizeRow> sizeRows) {
    }

    /** 尺码明细，一行一个尺码；列顺序与「指定尺码改价」模板一致。 */
    public record SizeRow(String itemId, String size, BigDecimal price, String sku, int quantity, int sold,
                          String euSize) {
    }

    void runFetch(Long taskId, String scope, int viewDays, AtomicBoolean cancelled) {
        Instant now = clock.instant();
        List<SellerListing> listings = new ArrayList<>();
        JSONObject progress = new JSONObject(true);
        if (!SCOPE_ENDED.equals(scope)) {
            fetchWindow(taskId, now, now.plus(Duration.ofDays(ENDED_LOOKBACK_DAYS)), listings, cancelled, progress,
                    "active");
        }
        if (!SCOPE_ACTIVE.equals(scope)) {
            fetchWindow(taskId, now.minus(Duration.ofDays(ENDED_LOOKBACK_DAYS)), now, listings, cancelled, progress,
                    "ended");
        }
        if (cancelled.get()) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
            return;
        }
        // 同一刊登可能出现在两个窗口的边界，去重。
        Map<String, SellerListing> byId = new LinkedHashMap<>();
        for (SellerListing listing : listings) {
            byId.putIfAbsent(listing.itemId(), listing);
        }
        Map<String, Integer> views = new HashMap<>();
        String viewsProblem = null;
        try {
            views = fetchViews(new ArrayList<>(byId.keySet()), viewDays, cancelled);
        } catch (RuntimeException e) {
            viewsProblem = "浏览人数获取失败：" + safeError(e);
            log.warn("获取刊登：浏览人数获取失败, taskId:{}", taskId, e);
        }
        Lookup lookup = lookup(byId.values());
        List<ListingRow> rows = new ArrayList<>();
        int active = 0;
        int ended = 0;
        for (SellerListing listing : byId.values()) {
            ListingRow row = toRow(listing, lookup, viewsProblem == null ? views.getOrDefault(listing.itemId(), 0) : null);
            rows.add(row);
            if (listing.active()) {
                active++;
            } else {
                ended++;
            }
        }
        saveReport(taskId, rows);
        List<TaskItemDO> items = new ArrayList<>();
        for (ListingRow row : rows) {
            items.add(toItem(taskId, row));
        }
        for (TaskItemDO item : items) {
            taskItemMapper.insert(item);
        }
        taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                .fluentPut("total", rows.size())
                .fluentPut("active", active)
                .fluentPut("ended", ended)
                .fluentPut("relisted", rows.stream().filter(r -> r.relistParentId() != null).count())
                .fluentPut("viewDays", viewDays)
                .fluentPut("viewsAvailable", viewsProblem == null)
                .toJSONString());
        if (viewsProblem != null) {
            taskMapper.updateTaskFailReason(taskId, viewsProblem + "（其余数据正常，可在系统设置重新授权eBay后重跑）");
        }
        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        log.info("获取刊登完成, taskId:{}, total:{}, active:{}, ended:{}", taskId, rows.size(), active, ended);
    }

    private void fetchWindow(Long taskId, Instant from, Instant to, List<SellerListing> sink, AtomicBoolean cancelled,
                             JSONObject progress, String label) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (cancelled.get()) {
                return;
            }
            EbayTradingApiClient.SellerListingPage result = tradingClient.getSellerListings(from, to, page);
            sink.addAll(result.listings());
            progress.put(label + "Pages", page + "/" + Math.max(page, result.totalPages()));
            progress.put("fetched", sink.size());
            taskMapper.updateTaskAttributes(taskId, progress.toJSONString());
            if (result.listings().isEmpty() || page >= result.totalPages()) {
                return;
            }
        }
    }

    private Map<String, Integer> fetchViews(List<String> itemIds, int viewDays, AtomicBoolean cancelled) {
        Map<String, Integer> views = new HashMap<>();
        LocalDate to = LocalDate.now(clock.withZone(BEIJING));
        LocalDate from = to.minusDays(viewDays - 1L);
        for (List<String> batch : EbayAnalyticsApiClient.batches(itemIds)) {
            if (cancelled.get()) {
                break;
            }
            views.putAll(analyticsClient.getListingViews(batch, from, to, properties.getDefaultMarketplaceId()));
        }
        return views;
    }

    ListingRow toRow(SellerListing listing, Lookup lookup, Integer views) {
        List<EbayListingDO> mappings = lookup.byListing.getOrDefault(listing.itemId(), List.of());
        EbayListingDO any = null;
        for (ListingVariation variation : listing.variations()) {
            EbayListingDO mapping = variation.sku() == null ? null : lookup.bySku.get(variation.sku());
            if (mapping != null) {
                any = mapping;
                break;
            }
        }
        if (any == null && !mappings.isEmpty()) {
            any = mappings.get(0);
        }
        String styleId = any != null && notBlank(any.getStyleId()) ? any.getStyleId().trim() : styleFromTitle(listing.title());
        String brand = any != null && notBlank(any.getBrand()) ? any.getBrand().trim() : null;
        boolean system = isSystemListing(listing, lookup);

        List<SizeRow> sizeRows = new ArrayList<>();
        List<String> onSale = new ArrayList<>();
        int quantity = 0;
        int sold = 0;
        BigDecimal min = null;
        BigDecimal max = null;
        for (ListingVariation variation : listing.variations()) {
            EbayListingDO mapping = variation.sku() == null ? null : lookup.bySku.get(variation.sku());
            String euSize = mapping == null ? null : mapping.getEuSize();
            sizeRows.add(new SizeRow(listing.itemId(), variation.size(), variation.price(), variation.sku(),
                    variation.available(), variation.sold(), euSize));
            quantity += variation.available();
            sold += variation.sold();
            if (variation.available() > 0) {
                if (variation.size() != null) {
                    onSale.add(variation.size());
                }
                if (variation.price() != null) {
                    min = min == null || variation.price().compareTo(min) < 0 ? variation.price() : min;
                    max = max == null || variation.price().compareTo(max) > 0 ? variation.price() : max;
                }
            }
        }
        if (min == null) {
            // 全部尺码都卖完/清零时，价格区间按全部尺码算，避免空白。
            for (ListingVariation variation : listing.variations()) {
                if (variation.price() != null) {
                    min = min == null || variation.price().compareTo(min) < 0 ? variation.price() : min;
                    max = max == null || variation.price().compareTo(max) > 0 ? variation.price() : max;
                }
            }
        }
        onSale.sort(EbayListingManageService::compareSize);
        String status = listing.active() ? "在售" : "已结束";
        String source = system ? "系统上架" : listing.relistParentId() != null ? "重新上架" : "其它";
        return new ListingRow(listing.itemId(), status, brand, styleId, listing.title(), String.join(",", onSale),
                onSale.size(), quantity, sold, min, max, listing.active() ? humanDuration(listing.timeLeft()) : "-",
                format(listing.endTime()), views, listing.watchCount(), format(listing.startTime()),
                listing.sku(), source, listing.relistParentId(), listing.relistedItemId(), listing.categoryName(),
                listing.viewItemUrl() == null ? "https://www.ebay.com/itm/" + listing.itemId() : listing.viewItemUrl(),
                List.copyOf(sizeRows));
    }

    private TaskItemDO toItem(Long taskId, ListingRow row) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setListingId(row.itemId());
        item.setBrand(truncate(row.brand(), 64));
        item.setTitle(truncate(row.title(), 255));
        item.setStyleId(truncate(row.styleId(), 64));
        item.setSku(truncate(row.sku(), 50));
        item.setSize(truncate(row.sizeCount() + "个尺码", 16));
        item.setListingQuantity(row.quantity());
        item.setSalesCount7d(row.views());
        item.setHighestBidCount(row.watchers());
        item.setSalesCount30d(row.sold());
        item.setLowestPrice(row.minPrice());
        item.setCurrentPrice(row.maxPrice());
        item.setOrderStatus(row.status());
        item.setSalesActivity(truncate(row.timeLeft(), 16));
        item.setReferencePriceLabel(truncate(row.source(), 32));
        item.setOperateResult(truncate(row.status() + " | " + row.source() + " | 在售尺码 " + row.sizes(), 255));
        item.setOperateTime(new Date());
        return item;
    }

    public Optional<List<ListingRow>> loadReport(Long taskId) {
        Path path = reportPath(taskId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(REPORT_MAPPER.readValue(Files.readString(path),
                    new com.fasterxml.jackson.core.type.TypeReference<List<ListingRow>>() {
                    }));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读取eBay刊登报告失败: " + taskId, e);
        }
    }

    private void saveReport(Long taskId, List<ListingRow> rows) {
        Path path = reportPath(taskId);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, REPORT_MAPPER.writeValueAsString(rows));
        } catch (IOException e) {
            throw new IllegalStateException("保存eBay刊登报告失败", e);
        }
    }

    private Path reportPath(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("非法任务ID");
        }
        return reportRoot.resolve(String.valueOf(taskId)).resolve(REPORT_FILE).normalize();
    }

    // ==================== 按编号下架 ====================

    void runEnd(Long taskId, List<String> itemIds, AtomicBoolean cancelled) {
        int ended = 0;
        int already = 0;
        int failed = 0;
        int done = 0;
        for (String itemId : itemIds) {
            if (cancelled.get()) {
                break;
            }
            SellerListing listing = null;
            String result;
            try {
                listing = tradingClient.getItem(itemId);
                if (!listing.active()) {
                    already++;
                    result = "已是下架状态";
                } else {
                    Lookup lookup = lookup(List.of(listing));
                    if (isSystemListing(listing, lookup)) {
                        endSystemListing(listing, lookup);
                        result = "下架成功(系统刊登)";
                    } else {
                        result = tradingClient.endItem(itemId) ? "下架成功" : "已是下架状态";
                    }
                    if (result.startsWith("下架成功")) {
                        ended++;
                    } else {
                        already++;
                    }
                    markEnded(itemId);
                }
            } catch (Exception e) {
                failed++;
                result = "下架失败(" + safeError(e) + ")";
                log.warn("按编号下架失败, taskId:{}, itemId:{}", taskId, itemId, e);
            }
            recordListing(taskId, itemId, listing, result);
            done++;
            writeProgress(taskId, itemIds.size(), done, new JSONObject(true)
                    .fluentPut("ended", ended).fluentPut("alreadyEnded", already).fluentPut("failed", failed));
        }
        finish(taskId, cancelled, failed, "下架完成：成功 " + (ended + already) + "，失败 " + failed);
    }

    /**
     * 系统刊登：先撤回挂在该刊登上的 offer（多尺码按商品组整组撤回），让 offer 记录和刊登一起结束；
     * 撤回后 eBay 仍显示在售时，再用 EndItem 兜底。
     */
    private void endSystemListing(SellerListing listing, Lookup lookup) {
        String groupKey = listing.hasVariations() ? listing.sku() : null;
        boolean withdrawn = false;
        if (groupKey != null) {
            try {
                sellClient.withdrawOfferByInventoryItemGroup(groupKey, properties.getDefaultMarketplaceId());
                withdrawn = true;
            } catch (EbayApiException e) {
                log.warn("eBay整组撤回失败，改用EndItem, itemId:{}, group:{}, {}", listing.itemId(), groupKey,
                        safeError(e));
            }
        } else {
            EbayListingDO mapping = lookup.bySku.get(listing.sku());
            if (mapping != null && notBlank(mapping.getOfferId())) {
                try {
                    sellClient.withdrawOffer(mapping.getOfferId());
                    withdrawn = true;
                } catch (EbayApiException e) {
                    log.warn("eBay撤回offer失败，改用EndItem, itemId:{}, {}", listing.itemId(), safeError(e));
                }
            }
        }
        if (!withdrawn) {
            tradingClient.endItem(listing.itemId());
        }
    }

    private void markEnded(String itemId) {
        try {
            listingMapper.markListingEnded(itemId);
        } catch (Exception e) {
            log.warn("eBay映射状态更新失败, listingId:{}", itemId, e);
        }
    }

    // ==================== 按编号重新上架 ====================

    void runRelist(Long taskId, List<String> itemIds, AtomicBoolean cancelled) {
        int relisted = 0;
        int skipped = 0;
        int failed = 0;
        int done = 0;
        for (String itemId : itemIds) {
            if (cancelled.get()) {
                break;
            }
            SellerListing listing = null;
            String result;
            String newItemId = null;
            try {
                listing = tradingClient.getItem(itemId);
                if (listing.active()) {
                    skipped++;
                    result = "跳过(刊登仍在售)";
                } else if (notBlank(listing.relistedItemId())) {
                    skipped++;
                    result = "跳过(已重新上架为 " + listing.relistedItemId() + ")";
                } else {
                    Lookup lookup = lookup(List.of(listing));
                    newItemId = isSystemListing(listing, lookup) && listing.hasVariations()
                            ? republishSystemGroup(listing)
                            : null;
                    if (newItemId == null) {
                        newItemId = tradingClient.relistFixedPriceItem(itemId);
                    }
                    relink(itemId, newItemId);
                    relisted++;
                    result = "重新上架成功，新编号 " + newItemId;
                }
            } catch (Exception e) {
                failed++;
                result = "重新上架失败(" + safeError(e) + ")";
                log.warn("按编号重新上架失败, taskId:{}, itemId:{}", taskId, itemId, e);
            }
            TaskItemDO item = recordListing(taskId, itemId, listing, result);
            if (newItemId != null && item != null) {
                // offerId 列在该任务里存新编号，便于导出对照。
                item.setOfferId(newItemId);
                taskItemMapper.updateById(item);
            }
            done++;
            writeProgress(taskId, itemIds.size(), done, new JSONObject(true)
                    .fluentPut("relisted", relisted).fluentPut("skipped", skipped).fluentPut("failed", failed));
        }
        finish(taskId, cancelled, failed, "重新上架完成：成功 " + relisted + "，跳过 " + skipped + "，失败 " + failed);
    }

    /**
     * 系统多尺码刊登按商品组重新发布，offer 记录跟着恢复。发布出来的仍是原编号时说明 eBay
     * 不认这个组了，返回 null 交给 RelistFixedPriceItem。
     */
    private String republishSystemGroup(SellerListing listing) {
        try {
            String listingId = sellClient.publishOfferByInventoryItemGroup(listing.sku(),
                    properties.getDefaultMarketplaceId());
            return notBlank(listingId) && !listingId.equals(listing.itemId()) ? listingId : null;
        } catch (EbayApiException e) {
            log.warn("eBay整组重新发布失败，改用RelistFixedPriceItem, itemId:{}, {}", listing.itemId(), safeError(e));
            return null;
        }
    }

    private void relink(String oldItemId, String newItemId) {
        try {
            listingMapper.relinkListing(oldItemId, newItemId);
        } catch (Exception e) {
            log.warn("eBay映射刊登号更新失败, {} -> {}", oldItemId, newItemId, e);
        }
    }

    // ==================== 指定尺码改价 ====================

    /** 一行改价目标：size 与 sku 至少一个；size 按 eBay 上显示的尺码值匹配（例如 9.5）。 */
    record PriceTarget(String itemId, String size, String sku, BigDecimal price) {
        EbaySetPriceExcel toExcel() {
            EbaySetPriceExcel excel = new EbaySetPriceExcel();
            excel.setItemId(itemId);
            excel.setSize(size);
            excel.setSku(sku);
            excel.setPrice(price.toPlainString());
            return excel;
        }
    }

    void runSetPrice(Long taskId, List<PriceTarget> targets, AtomicBoolean cancelled) {
        Map<String, List<PriceTarget>> byItem = new LinkedHashMap<>();
        for (PriceTarget target : targets) {
            byItem.computeIfAbsent(target.itemId(), key -> new ArrayList<>()).add(target);
        }
        int changed = 0;
        int unchanged = 0;
        int failed = 0;
        int done = 0;
        for (Map.Entry<String, List<PriceTarget>> entry : byItem.entrySet()) {
            if (cancelled.get()) {
                break;
            }
            String itemId = entry.getKey();
            SellerListing listing;
            Lookup lookup;
            try {
                listing = tradingClient.getItem(itemId);
                lookup = lookup(List.of(listing));
            } catch (Exception e) {
                for (PriceTarget target : entry.getValue()) {
                    failed++;
                    recordPrice(taskId, target, null, null, "改价失败(读取刊登：" + safeError(e) + ")");
                }
                done += entry.getValue().size();
                writeProgress(taskId, targets.size(), done, priceAttrs(changed, unchanged, failed));
                continue;
            }
            for (PriceTarget target : entry.getValue()) {
                if (cancelled.get()) {
                    break;
                }
                String result;
                ListingVariation variation = null;
                try {
                    if (!listing.active()) {
                        throw new IllegalStateException("刊登已结束");
                    }
                    variation = match(listing, target);
                    if (variation.price() != null && variation.price().compareTo(target.price()) == 0) {
                        unchanged++;
                        result = "价格未变($" + target.price().toPlainString() + ")";
                    } else {
                        setPrice(listing, variation, target.price(), lookup);
                        changed++;
                        result = "改价成功 $" + plain(variation.price()) + "→$" + target.price().toPlainString();
                    }
                } catch (Exception e) {
                    failed++;
                    result = "改价失败(" + safeError(e) + ")";
                    log.warn("指定尺码改价失败, taskId:{}, itemId:{}, size:{}", taskId, itemId, target.size(), e);
                }
                recordPrice(taskId, target, listing, variation, result);
                done++;
                writeProgress(taskId, targets.size(), done, priceAttrs(changed, unchanged, failed));
            }
        }
        finish(taskId, cancelled, failed, "改价完成：成功 " + (changed + unchanged) + "，失败 " + failed);
    }

    private static JSONObject priceAttrs(int changed, int unchanged, int failed) {
        return new JSONObject(true).fluentPut("changed", changed).fluentPut("unchanged", unchanged)
                .fluentPut("failed", failed);
    }

    static ListingVariation match(SellerListing listing, PriceTarget target) {
        if (notBlank(target.sku())) {
            for (ListingVariation variation : listing.variations()) {
                if (target.sku().equalsIgnoreCase(nullToEmpty(variation.sku()))) {
                    return variation;
                }
            }
            if (!notBlank(target.size())) {
                throw new IllegalArgumentException("刊登里没有SKU " + target.sku());
            }
        }
        if (!listing.hasVariations()) {
            if (notBlank(target.size()) && listing.variations().get(0).size() != null
                    && !sameSize(listing.variations().get(0).size(), target.size())) {
                throw new IllegalArgumentException("单尺码刊登，尺码对不上");
            }
            return listing.variations().get(0);
        }
        if (!notBlank(target.size())) {
            throw new IllegalArgumentException("多尺码刊登需要填写尺码");
        }
        List<ListingVariation> matches = new ArrayList<>();
        for (ListingVariation variation : listing.variations()) {
            if (variation.size() != null && sameSize(variation.size(), target.size())) {
                matches.add(variation);
            }
        }
        if (matches.isEmpty()) {
            List<String> sizes = listing.variations().stream().map(ListingVariation::size)
                    .filter(EbayListingManageService::notBlank).sorted(EbayListingManageService::compareSize).toList();
            throw new IllegalArgumentException("刊登里没有尺码 " + target.size() + "（现有：" + String.join(",", sizes) + "）");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("尺码 " + target.size() + " 匹配到多个，请填写SKU");
        }
        return matches.get(0);
    }

    /**
     * 先改刊登本身（买家看到的价格，以它为准）；系统刊登再把 offer 记录同步成同一个价，
     * 避免之后按 offer 的改价把价格改回去。实测过 offer 改了刊登却没跟着变的情况，所以两边都改。
     */
    private void setPrice(SellerListing listing, ListingVariation variation, BigDecimal price, Lookup lookup) {
        tradingClient.reviseInventoryStatus(List.of(new EbayTradingApiClient.InventoryRevision(listing.itemId(),
                listing.hasVariations() ? variation.sku() : null, price, null)));
        EbayListingDO mapping = variation.sku() == null ? null : lookup.bySku.get(variation.sku());
        boolean systemOffer = mapping != null && notBlank(mapping.getOfferId())
                && listing.itemId().equals(nullToEmpty(mapping.getListingId()).trim());
        if (!systemOffer) {
            return;
        }
        try {
            List<EbaySellApiClient.OfferUpdateResult> results = sellClient.bulkUpdatePriceQuantity(List.of(
                    new EbaySellApiClient.OfferPriceQuantity(variation.sku(), mapping.getOfferId(), price,
                            properties.getDefaultCurrency(), null)));
            if (!results.isEmpty() && !results.get(0).success()) {
                log.warn("eBay offer价格同步失败(刊登已改好), sku:{}, {}", variation.sku(), results.get(0).error());
            }
            listingMapper.updatePriceAndQuantity(variation.sku(), price, null);
        } catch (Exception e) {
            log.warn("eBay offer价格同步失败(刊登已改好), sku:{}", variation.sku(), e);
        }
    }

    // ==================== 公共 ====================

    /** 本地映射：按 SKU、按刊登号。 */
    record Lookup(Map<String, EbayListingDO> bySku, Map<String, List<EbayListingDO>> byListing) {
    }

    Lookup lookup(java.util.Collection<SellerListing> listings) {
        Set<String> skus = new LinkedHashSet<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (SellerListing listing : listings) {
            itemIds.add(listing.itemId());
            for (ListingVariation variation : listing.variations()) {
                if (notBlank(variation.sku())) {
                    skus.add(variation.sku());
                }
            }
        }
        Map<String, EbayListingDO> bySku = new HashMap<>();
        Map<String, List<EbayListingDO>> byListing = new HashMap<>();
        try {
            for (List<String> batch : batches(skus)) {
                for (EbayListingDO mapping : listingMapper.selectBySkus(batch)) {
                    bySku.putIfAbsent(mapping.getSku(), mapping);
                }
            }
            for (List<String> batch : batches(itemIds)) {
                for (EbayListingDO mapping : listingMapper.selectByListingIds(batch)) {
                    byListing.computeIfAbsent(mapping.getListingId(), key -> new ArrayList<>()).add(mapping);
                }
            }
        } catch (RuntimeException e) {
            log.warn("eBay刊登管理：读取本地上架映射失败，货号将从标题提取", e);
        }
        return new Lookup(bySku, byListing);
    }

    /** 系统刊登 = 本地映射里有 SKU 记在这个刊登号上（重新上架出来的副本刊登号不同，不算）。 */
    static boolean isSystemListing(SellerListing listing, Lookup lookup) {
        if (lookup.byListing.containsKey(listing.itemId())) {
            return true;
        }
        for (ListingVariation variation : listing.variations()) {
            EbayListingDO mapping = variation.sku() == null ? null : lookup.bySku.get(variation.sku());
            if (mapping != null && listing.itemId().equals(nullToEmpty(mapping.getListingId()).trim())) {
                return true;
            }
        }
        return false;
    }

    private TaskItemDO recordListing(Long taskId, String itemId, SellerListing listing, String result) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setListingId(itemId);
        if (listing != null) {
            item.setTitle(truncate(listing.title(), 255));
            item.setSku(truncate(listing.sku(), 50));
            item.setStyleId(truncate(styleFromTitle(listing.title()), 64));
            item.setOrderStatus(listing.active() ? "在售" : "已结束");
        }
        item.setOperateResult(truncate(result, 255));
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
        return item;
    }

    private void recordPrice(Long taskId, PriceTarget target, SellerListing listing, ListingVariation variation,
                             String result) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setListingId(target.itemId());
        item.setSize(truncate(variation != null && variation.size() != null ? variation.size() : target.size(), 16));
        item.setSku(truncate(variation != null && variation.sku() != null ? variation.sku() : target.sku(), 50));
        if (listing != null) {
            item.setTitle(truncate(listing.title(), 255));
            item.setStyleId(truncate(styleFromTitle(listing.title()), 64));
        }
        item.setCurrentPrice(variation == null ? null : variation.price());
        item.setTargetPrice(target.price());
        item.setOperateResult(truncate(result, 255));
        item.setOperateTime(new Date());
        taskItemMapper.insert(item);
    }

    private void writeProgress(Long taskId, int total, int done, JSONObject counters) {
        JSONObject attributes = new JSONObject(true).fluentPut("total", total).fluentPut("done", done);
        attributes.putAll(counters);
        taskMapper.updateTaskAttributes(taskId, attributes.toJSONString());
    }

    private void finish(Long taskId, AtomicBoolean cancelled, int failed, String summary) {
        if (cancelled.get()) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
        } else if (failed == 0) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        } else {
            taskMapper.updateTaskFailed(taskId, summary + "，请查看任务明细");
        }
    }

    // ==================== 输入解析 ====================

    static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SCOPE_ACTIVE;
        }
        String value = scope.trim().toLowerCase(Locale.ROOT);
        if (!Set.of(SCOPE_ACTIVE, SCOPE_ENDED, SCOPE_ALL).contains(value)) {
            throw new IllegalArgumentException("不支持的刊登范围：" + scope);
        }
        return value;
    }

    /** 物品编号：纯数字，或包含 /itm/编号 的链接。 */
    static String parseItemId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
        }
        if (ITEM_ID.matcher(value).matches()) {
            return value;
        }
        Matcher matcher = ITEM_URL.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }
        try {
            // Excel 把长数字存成科学计数法时（4.07333465699E11）还原。
            String plain = new BigDecimal(value).toPlainString();
            return ITEM_ID.matcher(plain).matches() ? plain : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<String> normalizeItemIds(List<EbayItemIdExcel> rows) {
        Set<String> itemIds = new LinkedHashSet<>();
        int line = 1;
        for (EbayItemIdExcel row : rows == null ? List.<EbayItemIdExcel>of() : rows) {
            line++;
            if (row == null || row.getItemId() == null || row.getItemId().isBlank()) {
                continue;
            }
            String itemId = parseItemId(row.getItemId());
            if (itemId == null) {
                throw new IllegalArgumentException("第" + line + "行物品编号格式错误：" + row.getItemId());
            }
            itemIds.add(itemId);
        }
        if (itemIds.isEmpty()) {
            throw new IllegalArgumentException("Excel里没有物品编号");
        }
        if (itemIds.size() > MAX_INPUT_ROWS) {
            throw new IllegalArgumentException("单次最多" + MAX_INPUT_ROWS + "个物品编号");
        }
        return List.copyOf(itemIds);
    }

    static List<PriceTarget> normalizePriceTargets(List<EbaySetPriceExcel> rows) {
        Map<String, PriceTarget> targets = new LinkedHashMap<>();
        int line = 1;
        for (EbaySetPriceExcel row : rows == null ? List.<EbaySetPriceExcel>of() : rows) {
            line++;
            if (row == null || row.getPrice() == null || row.getPrice().isBlank()) {
                continue;
            }
            String itemId = parseItemId(row.getItemId());
            if (itemId == null) {
                throw new IllegalArgumentException("第" + line + "行物品编号格式错误：" + row.getItemId());
            }
            String size = row.getSize() == null || row.getSize().isBlank() ? null : normalizeSize(row.getSize());
            String sku = row.getSku() == null || row.getSku().isBlank() ? null : row.getSku().trim();
            BigDecimal price;
            try {
                price = new BigDecimal(row.getPrice().trim().replace("$", "").replace(",", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("第" + line + "行价格格式错误：" + row.getPrice());
            }
            if (price.compareTo(new BigDecimal("0.99")) < 0 || price.compareTo(new BigDecimal("99999")) > 0) {
                throw new IllegalArgumentException("第" + line + "行价格需在0.99到99999美元之间");
            }
            if (price.scale() > 2) {
                throw new IllegalArgumentException("第" + line + "行价格最多两位小数");
            }
            String key = itemId + "|" + (sku != null ? sku.toUpperCase(Locale.ROOT) : "size:" + size);
            // 同一尺码写了多行时以最后一行为准。
            targets.remove(key);
            targets.put(key, new PriceTarget(itemId, size, sku, price.setScale(2, RoundingMode.UNNECESSARY)));
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Excel里没有要改价的行（新价格为空的行会被忽略）");
        }
        if (targets.size() > MAX_INPUT_ROWS) {
            throw new IllegalArgumentException("单次最多" + MAX_INPUT_ROWS + "行");
        }
        return List.copyOf(targets.values());
    }

    private static List<EbayItemIdExcel> toRows(List<String> itemIds) {
        return itemIds.stream().map(id -> {
            EbayItemIdExcel row = new EbayItemIdExcel();
            row.setItemId(id);
            return row;
        }).toList();
    }

    /** 尺码只保留数字部分比较：「US 9.5」「9.5」「9.50」都算 9.5。 */
    static String normalizeSize(String size) {
        String value = size.trim().toUpperCase(Locale.ROOT).replaceAll("^(US[MW]?|EU|UK)\\s*", "").trim();
        try {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    static boolean sameSize(String a, String b) {
        return normalizeSize(a).equalsIgnoreCase(normalizeSize(b));
    }

    static int compareSize(String a, String b) {
        try {
            return new BigDecimal(normalizeSize(a)).compareTo(new BigDecimal(normalizeSize(b)));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    static String styleFromTitle(String title) {
        if (title == null) {
            return null;
        }
        Matcher matcher = TITLE_STYLE.matcher(title.trim().replace("&apos;", "'"));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** ISO 8601 时长（P2DT10H37M34S）转成「2天10小时」。 */
    static String humanDuration(String iso) {
        if (iso == null || iso.isBlank()) {
            return "-";
        }
        try {
            String value = iso.trim();
            long days = 0;
            int t = value.indexOf('T');
            String datePart = t >= 0 ? value.substring(1, t) : value.substring(1);
            if (datePart.endsWith("D")) {
                days = Long.parseLong(datePart.substring(0, datePart.length() - 1));
            }
            Duration duration = t >= 0 ? Duration.parse("PT" + value.substring(t + 1)) : Duration.ZERO;
            long hours = duration.toHours();
            long minutes = duration.toMinutesPart();
            if (days > 0) {
                return days + "天" + hours + "小时";
            }
            if (hours > 0) {
                return hours + "小时" + minutes + "分";
            }
            return minutes + "分";
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String format(Instant instant) {
        return instant == null ? "" : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(BEIJING).format(instant);
    }

    private static List<List<String>> batches(Set<String> values) {
        List<List<String>> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String value : values) {
            current.add(value);
            if (current.size() >= 500) {
                result.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "?" : value.toPlainString();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 160 ? message : message.substring(0, 160);
    }
}
