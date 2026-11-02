package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.ProductCatalogMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.ProductCatalogDO;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 一次性补数据：给已在架商品组的标题在货号前补上性别/年龄段
 * (Men's / Women's / Grade School / Preschool / Toddler)。
 * 性别取商品组上架时写入的 Department，保证和类目一致；已经是新标题的组直接跳过，可重复执行。
 * 资料库里标记为手工资料的货号保持原标题。
 */
@Slf4j
@Service
public class EbayTitleBackfillService {

    private static final int MAX_SAMPLES = 50;

    private final EbayListingMapper listingMapper;
    private final ProductCatalogMapper catalogMapper;
    private final EbayListingService listingService;
    private final EbayTitleService titleService;
    private final EbayProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> phase = new AtomicReference<>("idle");
    private final AtomicBoolean dryRun = new AtomicBoolean(true);
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger updated = new AtomicInteger();
    private final AtomicInteger unchanged = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicReference<String> lastMessage = new AtomicReference<>("");
    private final List<Map<String, String>> samples = new ArrayList<>();

    public EbayTitleBackfillService(EbayListingMapper listingMapper,
                                    ProductCatalogMapper catalogMapper,
                                    EbayListingService listingService,
                                    EbayTitleService titleService,
                                    EbayProperties properties) {
        this.listingMapper = listingMapper;
        this.catalogMapper = catalogMapper;
        this.listingService = listingService;
        this.titleService = titleService;
        this.properties = properties;
    }

    /**
     * @param dryRunMode true 时只计算新标题，不写 eBay 也不写库
     * @param limit      最多处理多少个商品组，<=0 表示不限
     * @param styleIds   非空时只处理这些货号
     */
    public boolean start(boolean dryRunMode, int limit, List<String> styleIds) {
        return start(dryRunMode, limit, styleIds, 1);
    }

    /** @param concurrency 同时处理的商品组数，1~8 */
    public boolean start(boolean dryRunMode, int limit, List<String> styleIds, int concurrency) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        cancelled.set(false);
        Set<String> only = styleIds == null ? Set.of() : styleIds.stream()
                .map(EbayTitleBackfillService::normalize).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Thread.ofVirtual().name("Ebay-Title-Backfill").start(() -> {
            try {
                run(dryRunMode, limit, only, Math.max(1, Math.min(8, concurrency)));
                phase.set(cancelled.get() ? "cancelled" : "finished");
            } catch (Exception e) {
                log.error("eBay标题补数据任务异常", e);
                lastMessage.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                phase.set("failed");
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        status.put("phase", phase.get());
        status.put("dryRun", dryRun.get());
        status.put("total", total.get());
        status.put("done", done.get());
        status.put("updated", updated.get());
        status.put("unchanged", unchanged.get());
        status.put("skipped", skipped.get());
        status.put("failed", failed.get());
        status.put("lastMessage", lastMessage.get());
        synchronized (samples) {
            status.put("samples", List.copyOf(samples));
        }
        return status;
    }

    void run(boolean dryRunMode, int limit, Set<String> only) {
        run(dryRunMode, limit, only, 1);
    }

    void run(boolean dryRunMode, int limit, Set<String> only, int concurrency) {
        dryRun.set(dryRunMode);
        Map<String, GroupTarget> groups = new LinkedHashMap<>();
        for (EbayListingDO listing : listingMapper.selectActive()) {
            String styleId = normalize(listing.getStyleId());
            if (styleId == null || (!only.isEmpty() && !only.contains(styleId))) {
                continue;
            }
            EbayListingRegion region = EbayListingRegion.fromCode(listing.getRegion());
            groups.computeIfAbsent(styleId + "|" + region.getCode(),
                    key -> new GroupTarget(styleId, region, sizeSystem(listing.getSize())));
        }
        Set<String> manualStyles = catalogMapper.selectList(new QueryWrapper<ProductCatalogDO>()
                        .select("model_no").eq("manual_override", true)).stream()
                .map(row -> normalize(row.getModelNo())).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<GroupTarget> targets = new ArrayList<>(groups.values());
        if (limit > 0 && targets.size() > limit) {
            targets = targets.subList(0, limit);
        }
        resetCounters(targets.size());
        log.info("eBay标题补数据开始, dryRun:{}, 商品组数:{}", dryRun.get(), targets.size());
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(concurrency);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (GroupTarget target : targets) {
                if (cancelled.get()) {
                    break;
                }
                permits.acquireUninterruptibly();
                executor.submit(() -> {
                    try {
                        processSafely(target, manualStyles);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        log.info("eBay标题补数据完成, {}", status());
    }

    private void processSafely(GroupTarget target, Set<String> manualStyles) {
        if (cancelled.get()) {
            return;
        }
        try {
            if (manualStyles.contains(target.styleId())) {
                skipped.incrementAndGet();
                return;
            }
            process(target);
        } catch (Exception e) {
            failed.incrementAndGet();
            lastMessage.set(target.styleId() + ": " + e.getMessage());
            log.warn("eBay标题补数据失败, styleId:{}, region:{}, reason:{}",
                    target.styleId(), target.region().getCode(), e.getMessage());
        } finally {
            done.incrementAndGet();
        }
    }

    private void process(GroupTarget target) {
        String groupKey = EbayListingFactory.inventoryGroupKey(target.styleId(), target.region());
        JSONObject group = listingService.getGroup(groupKey).orElse(null);
        String oldTitle = group == null ? null : group.getString("title");
        if (oldTitle == null || oldTitle.isBlank()) {
            skipped.incrementAndGet();
            return;
        }
        String newTitle = newTitle(target.styleId(), target.sizeSystem(), oldTitle, department(group));
        if (newTitle.equals(oldTitle.trim())) {
            unchanged.incrementAndGet();
            return;
        }
        addSample(target, oldTitle, newTitle);
        if (!dryRun.get()) {
            listingService.replaceGroupTitle(groupKey, group, newTitle,
                    properties.getDefaultContentLanguage());
            listingMapper.updateGroupTitle(target.styleId(), target.region().getCode(), newTitle);
        }
        updated.incrementAndGet();
    }

    String newTitle(String styleId, String sizeSystem, String oldTitle, String department) {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle(oldTitle);
        // 历史上 EU 码且未识别性别的商品按男鞋上架，这里保持一致。
        String effectiveDepartment = department == null && "EU".equals(sizeSystem) ? "Men" : department;
        return titleService.generate(styleId, sizeSystem, metadata, effectiveDepartment);
    }

    static String department(JSONObject group) {
        JSONObject aspects = group.getJSONObject("aspects");
        if (aspects == null) {
            return null;
        }
        for (String key : aspects.keySet()) {
            if ("department".equalsIgnoreCase(key)) {
                JSONArray values = aspects.getJSONArray(key);
                return values == null || values.isEmpty() ? null : values.getString(0);
            }
        }
        return null;
    }

    private void addSample(GroupTarget target, String oldTitle, String newTitle) {
        synchronized (samples) {
            if (samples.size() < MAX_SAMPLES) {
                Map<String, String> sample = new LinkedHashMap<>();
                sample.put("styleId", target.styleId());
                sample.put("region", target.region().getCode());
                sample.put("old", oldTitle);
                sample.put("new", newTitle);
                samples.add(sample);
            }
        }
    }

    private void resetCounters(int totalCount) {
        phase.set("running");
        total.set(totalCount);
        done.set(0);
        updated.set(0);
        unchanged.set(0);
        skipped.set(0);
        failed.set(0);
        lastMessage.set("");
        synchronized (samples) {
            samples.clear();
        }
    }

    private static String sizeSystem(String size) {
        String normalized = size == null ? "" : size.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("USM")) {
            return "USM";
        }
        if (normalized.startsWith("USW")) {
            return "USW";
        }
        return normalized.startsWith("EU") ? "EU" : null;
    }

    private static String normalize(String styleId) {
        return styleId == null || styleId.isBlank() ? null : styleId.trim().toUpperCase(Locale.ROOT);
    }

    private record GroupTarget(String styleId, EbayListingRegion region, String sizeSystem) {
    }
}
