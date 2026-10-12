package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayQuotaExceededException;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.mapper.EbayListingImageStateMapper;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.ProductCatalogMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.ProductCatalogDO;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次性补数据：
 * 1. 资料库里不是 StockX 来源或图片不足 5 张的货号，按"StockX 优先、不足 5 张用 KC 补"重新抓取；
 * 2. 已在 eBay 上架、但当前图片不是 StockX 的商品组，把组图和各尺码图换成资料库里的 StockX 图。
 * 第 2 步每张图都要消耗 eBay 每日 5000 次的图片上传额度，额度用完即停，次日再跑会从未完成的货号继续。
 */
@Slf4j
@Service
public class EbayImageBackfillService {

    private static final String SOURCE_STOCKX = "stockx";
    private static final long STOCKX_BACKOFF_MS = 60_000L;
    private static final int MAX_STOCKX_RETRIES = 5;

    private final ProductCatalogMapper catalogMapper;
    private final EbayListingMapper listingMapper;
    private final EbayProductMetadataService metadataService;
    private final EbayListingService listingService;
    private final EbayListingFactory listingFactory;
    private final EbayProperties properties;
    private final EbayListingImageStateMapper imageStateMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> phase = new AtomicReference<>("idle");
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicReference<String> lastMessage = new AtomicReference<>("");

    public EbayImageBackfillService(ProductCatalogMapper catalogMapper,
                                    EbayListingMapper listingMapper,
                                    EbayProductMetadataService metadataService,
                                    EbayListingService listingService,
                                    EbayListingFactory listingFactory,
                                    EbayProperties properties,
                                    EbayListingImageStateMapper imageStateMapper) {
        this.catalogMapper = catalogMapper;
        this.listingMapper = listingMapper;
        this.metadataService = metadataService;
        this.listingService = listingService;
        this.listingFactory = listingFactory;
        this.properties = properties;
        this.imageStateMapper = imageStateMapper;
    }

    /**
     * @param refreshCatalog 是否执行第 1 步(重新抓资料)
     * @param replaceListing 是否执行第 2 步(替换已上架商品图片)
     * @param limit          第 2 步最多处理多少个货号，<=0 表示不限
     */
    public boolean start(boolean refreshCatalog, boolean replaceListing, int limit) {
        return start(refreshCatalog, replaceListing, limit, List.of());
    }

    /**
     * @param onlyStyleIds 非空时只处理这些货号（用于小批量验证），并且不受"已处理"状态限制
     */
    public boolean start(boolean refreshCatalog, boolean replaceListing, int limit,
                         List<String> onlyStyleIds) {
        List<String> only = onlyStyleIds == null ? List.of() : onlyStyleIds.stream()
                .map(EbayImageBackfillService::normalize).filter(java.util.Objects::nonNull).distinct().toList();
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        cancelled.set(false);
        Thread.ofVirtual().name("Ebay-Image-Backfill").start(() -> {
            try {
                if (refreshCatalog) {
                    refreshCatalog(only);
                }
                if (replaceListing && !cancelled.get()) {
                    replaceListingImages(limit, only);
                }
                phase.set(cancelled.get() ? "cancelled" : "finished");
            } catch (Exception e) {
                log.error("eBay图片补数据任务异常", e);
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
        status.put("total", total.get());
        status.put("done", done.get());
        status.put("succeeded", succeeded.get());
        status.put("skipped", skipped.get());
        status.put("failed", failed.get());
        status.put("lastMessage", lastMessage.get());
        return status;
    }

    /** 需要重抓的货号：资料库里非 StockX 来源或图片不足 5 张的，加上在架但不在资料库里的货号。 */
    List<String> catalogTargets() {
        Set<String> targets = new LinkedHashSet<>();
        for (ProductCatalogDO row : catalogMapper.selectList(
                new QueryWrapper<ProductCatalogDO>().select("model_no", "source", "image_urls"))) {
            List<String> images = parseImages(row.getImageUrls());
            if (!SOURCE_STOCKX.equals(row.getSource())
                    || images.size() < EbayProductMetadataService.MIN_PREFERRED_IMAGES) {
                targets.add(row.getModelNo());
            }
        }
        Set<String> cataloged = new LinkedHashSet<>();
        catalogMapper.selectList(new QueryWrapper<ProductCatalogDO>().select("model_no"))
                .forEach(row -> cataloged.add(row.getModelNo().toUpperCase(Locale.ROOT)));
        for (EbayListingDO listing : listingMapper.selectActive()) {
            String styleId = normalize(listing.getStyleId());
            if (styleId != null && !cataloged.contains(styleId)) {
                targets.add(styleId);
            }
        }
        return new ArrayList<>(targets);
    }

    private void refreshCatalog(List<String> only) {
        List<String> targets = only.isEmpty() ? catalogTargets() : only;
        resetCounters("refreshCatalog", targets.size());
        log.info("eBay图片补数据-重抓资料开始，货号数:{}", targets.size());
        for (String modelNo : targets) {
            if (cancelled.get()) {
                return;
            }
            try {
                EbayProductMetadata metadata = refreshWithBackoff(modelNo);
                if (metadata == null) {
                    return;
                }
                succeeded.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                lastMessage.set(modelNo + ": " + e.getMessage());
                log.warn("eBay图片补数据-重抓资料失败, modelNo:{}, reason:{}", modelNo, e.getMessage());
            } finally {
                done.incrementAndGet();
            }
        }
        log.info("eBay图片补数据-重抓资料完成, {}", status());
    }

    private EbayProductMetadata refreshWithBackoff(String modelNo) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return metadataService.refresh(modelNo);
            } catch (StockXRateLimitException e) {
                if (attempt >= MAX_STOCKX_RETRIES) {
                    throw e;
                }
                long wait = Math.max(e.getCooldownMs(), STOCKX_BACKOFF_MS);
                lastMessage.set("StockX限流，等待" + wait / 1000 + "秒后重试: " + modelNo);
                Thread.sleep(wait);
                if (cancelled.get()) {
                    return null;
                }
            }
        }
    }

    private void replaceListingImages(int limit, List<String> only) {
        Map<String, ProductCatalogDO> catalog = new LinkedHashMap<>();
        for (ProductCatalogDO row : catalogMapper.selectList(new QueryWrapper<>())) {
            catalog.put(row.getModelNo().toUpperCase(Locale.ROOT), row);
        }
        Set<String> activeStyles = new LinkedHashSet<>();
        for (EbayListingDO listing : listingMapper.selectActive()) {
            String styleId = normalize(listing.getStyleId());
            if (styleId != null) {
                activeStyles.add(styleId);
            }
        }
        // 只处理记录为 KC 图片的货号；替换成功后标记为 stockx，重跑时自动跳过。
        List<String> styles = (only.isEmpty() ? imageStateMapper.selectPendingStyleIds() : only).stream()
                .map(EbayImageBackfillService::normalize)
                .filter(activeStyles::contains)
                .toList();
        resetCounters("replaceListing", limit > 0 ? Math.min(limit, styles.size()) : styles.size());
        log.info("eBay图片补数据-替换已上架图片开始，货号数:{}", total.get());
        int processed = 0;
        for (String styleId : styles) {
            if (cancelled.get() || (limit > 0 && processed >= limit)) {
                return;
            }
            processed++;
            try {
                ProductCatalogDO row = catalog.get(styleId);
                List<String> images = row == null ? List.of() : parseImages(row.getImageUrls());
                if (row == null || !SOURCE_STOCKX.equals(row.getSource()) || images.isEmpty()) {
                    skipped.incrementAndGet();
                    continue;
                }
                int updatedGroups = 0;
                for (EbayListingRegion region : EbayListingRegion.values()) {
                    String groupKey = listingFactory.groupKey(styleId, region);
                    int items = listingService.replaceGroupImages(
                            groupKey, images, properties.getDefaultContentLanguage());
                    if (items >= 0) {
                        updatedGroups++;
                        log.info("eBay图片补数据-已替换商品组图片, styleId:{}, region:{}, items:{}",
                                styleId, region.getCode(), items);
                    }
                }
                imageStateMapper.markReplaced(styleId);
                if (updatedGroups > 0) {
                    succeeded.incrementAndGet();
                } else {
                    skipped.incrementAndGet();
                }
            } catch (EbayQuotaExceededException e) {
                failed.incrementAndGet();
                lastMessage.set("eBay图片上传额度已用完，任务暂停，额度重置后重新启动即可继续: " + e.getMessage());
                log.warn("eBay图片补数据-图片上传额度用完，停止, styleId:{}", styleId);
                return;
            } catch (Exception e) {
                failed.incrementAndGet();
                lastMessage.set(styleId + ": " + e.getMessage());
                log.warn("eBay图片补数据-替换图片失败, styleId:{}, reason:{}", styleId, e.getMessage());
            } finally {
                done.incrementAndGet();
            }
        }
        log.info("eBay图片补数据-替换已上架图片完成, {}", status());
    }

    private void resetCounters(String phaseName, int totalCount) {
        phase.set(phaseName);
        total.set(totalCount);
        done.set(0);
        succeeded.set(0);
        skipped.set(0);
        failed.set(0);
    }

    private static List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> images = JSON.parseArray(raw, String.class);
            return images == null ? List.of() : images;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String normalize(String styleId) {
        return styleId == null || styleId.isBlank() ? null : styleId.trim().toUpperCase(Locale.ROOT);
    }
}
