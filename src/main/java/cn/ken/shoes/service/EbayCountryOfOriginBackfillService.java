package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.EbayListingDO;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 一次性补数据：把已在架商品组的产地属性（Country of Origin）统一改成 Vietnam。
 * 已经是 Vietnam 的组直接跳过，可重复执行；只改商品组属性，价格、库存、标题等保持原值。
 */
@Slf4j
@Service
public class EbayCountryOfOriginBackfillService {

    private static final int MAX_SAMPLES = 50;
    private static final Set<String> ORIGIN_ASPECT_KEYS = Set.of(
            "countryoforigin", "countryregionofmanufacture");

    private final EbayListingMapper listingMapper;
    private final EbayListingService listingService;
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

    public EbayCountryOfOriginBackfillService(EbayListingMapper listingMapper,
                                              EbayListingService listingService,
                                              EbayProperties properties) {
        this.listingMapper = listingMapper;
        this.listingService = listingService;
        this.properties = properties;
    }

    /**
     * @param dryRunMode  true 时只统计要改的商品组，不写 eBay
     * @param limit       最多处理多少个商品组，<=0 表示不限
     * @param styleIds    非空时只处理这些货号
     * @param concurrency 同时处理的商品组数，1~8
     */
    public boolean start(boolean dryRunMode, int limit, List<String> styleIds, int concurrency) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        cancelled.set(false);
        Set<String> only = styleIds == null ? Set.of() : styleIds.stream()
                .map(EbayCountryOfOriginBackfillService::normalize).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Thread.ofVirtual().name("Ebay-Origin-Backfill").start(() -> {
            try {
                run(dryRunMode, limit, only, Math.max(1, Math.min(8, concurrency)));
                phase.set(cancelled.get() ? "cancelled" : "finished");
            } catch (Exception e) {
                log.error("eBay产地补数据任务异常", e);
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

    void run(boolean dryRunMode, int limit, Set<String> only, int concurrency) {
        dryRun.set(dryRunMode);
        Map<String, GroupTarget> groups = new LinkedHashMap<>();
        for (EbayListingDO listing : listingMapper.selectActive()) {
            String styleId = normalize(listing.getStyleId());
            if (styleId == null || (!only.isEmpty() && !only.contains(styleId))) {
                continue;
            }
            EbayListingRegion region = EbayListingRegion.fromCode(listing.getRegion());
            groups.putIfAbsent(styleId + "|" + region.getCode(), new GroupTarget(styleId, region));
        }
        List<GroupTarget> targets = new ArrayList<>(groups.values());
        if (limit > 0 && targets.size() > limit) {
            targets = targets.subList(0, limit);
        }
        resetCounters(targets.size());
        log.info("eBay产地补数据开始, dryRun:{}, 商品组数:{}", dryRunMode, targets.size());
        Semaphore permits = new Semaphore(concurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (GroupTarget target : targets) {
                if (cancelled.get()) {
                    break;
                }
                permits.acquireUninterruptibly();
                executor.submit(() -> {
                    try {
                        processSafely(target);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        log.info("eBay产地补数据完成, {}", status());
    }

    private void processSafely(GroupTarget target) {
        if (cancelled.get()) {
            return;
        }
        try {
            process(target);
        } catch (Exception e) {
            failed.incrementAndGet();
            lastMessage.set(target.styleId() + ": " + e.getMessage());
            log.warn("eBay产地补数据失败, styleId:{}, region:{}, reason:{}",
                    target.styleId(), target.region().getCode(), e.getMessage());
        } finally {
            done.incrementAndGet();
        }
    }

    private void process(GroupTarget target) {
        String groupKey = EbayListingFactory.inventoryGroupKey(target.styleId(), target.region());
        JSONObject group = listingService.getGroup(groupKey).orElse(null);
        if (group == null || group.getJSONArray("variantSKUs") == null
                || group.getJSONArray("variantSKUs").isEmpty()) {
            skipped.incrementAndGet();
            return;
        }
        JSONObject current = group.getJSONObject("aspects");
        JSONObject aspects = withVietnamOrigin(current);
        if (aspects == null) {
            unchanged.incrementAndGet();
            return;
        }
        addSample(target, originValue(current));
        if (!dryRun.get()) {
            listingService.replaceGroupAspects(groupKey, group, aspects,
                    properties.getDefaultContentLanguage());
        }
        updated.incrementAndGet();
    }

    /**
     * 返回把产地改成 Vietnam 后的属性；已经只有 Vietnam 时返回 null（无需修改）。
     * 旧的产地属性（含 Country/Region of Manufacture 写法）先去掉，统一写成 Country of Origin。
     */
    static JSONObject withVietnamOrigin(JSONObject aspects) {
        String target = EbayListingTaxonomyService.DEFAULT_COUNTRY_OF_ORIGIN;
        JSONObject result = new JSONObject(true);
        boolean alreadyVietnam = false;
        boolean otherOrigin = false;
        if (aspects != null) {
            for (Map.Entry<String, Object> entry : aspects.entrySet()) {
                if (!isOriginAspect(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                    continue;
                }
                JSONArray values = entry.getValue() instanceof JSONArray array ? array : null;
                boolean vietnamOnly = values != null && values.size() == 1
                        && target.equalsIgnoreCase(values.getString(0))
                        && EbayListingTaxonomyService.COUNTRY_OF_ORIGIN_ASPECT.equals(entry.getKey());
                if (vietnamOnly) {
                    alreadyVietnam = true;
                } else {
                    otherOrigin = true;
                }
            }
        }
        if (alreadyVietnam && !otherOrigin) {
            return null;
        }
        JSONArray values = new JSONArray();
        values.add(target);
        result.put(EbayListingTaxonomyService.COUNTRY_OF_ORIGIN_ASPECT, values);
        return result;
    }

    private static String originValue(JSONObject aspects) {
        if (aspects == null) {
            return "";
        }
        return aspects.entrySet().stream()
                .filter(entry -> isOriginAspect(entry.getKey()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private static boolean isOriginAspect(String name) {
        return name != null && ORIGIN_ASPECT_KEYS.contains(
                name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
    }

    private void addSample(GroupTarget target, String oldOrigin) {
        synchronized (samples) {
            if (samples.size() < MAX_SAMPLES) {
                Map<String, String> sample = new LinkedHashMap<>();
                sample.put("styleId", target.styleId());
                sample.put("region", target.region().getCode());
                sample.put("old", oldOrigin);
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

    private static String normalize(String styleId) {
        return styleId == null || styleId.isBlank() ? null : styleId.trim().toUpperCase(Locale.ROOT);
    }

    private record GroupTarget(String styleId, EbayListingRegion region) {
    }
}
