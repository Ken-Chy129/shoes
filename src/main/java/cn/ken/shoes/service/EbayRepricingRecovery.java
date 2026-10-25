package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.client.EbaySellApiClient.OfferPriceQuantity;
import cn.ken.shoes.client.EbaySellApiClient.OfferUpdateResult;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * 批量改价（bulkUpdatePriceQuantity）的统一出错处理，定时改价和盈利改价共用。
 *
 * <ul>
 *   <li>整批 4xx（如 "provider error"）：二分拆批重试，只让真正有问题的 offer 失败；</li>
 *   <li>单个 offer 5xx（如 25002 System error）：短暂退避后重试；</li>
 *   <li>25013 尺码已脱离 listing：清库存的直接视为完成；要写价格且有库存的整组重新发布后重试；
 *       库存为 0 的只做说明（eBay 已把它移出 listing，重新发布也挂不回去）；</li>
 *   <li>25004 清最后一个有货尺码被拒：说明整组都无价，整组下架并把映射标记为 ended，
 *       之后需重新上传 Excel 上架；</li>
 *   <li>25097 eBay 因违规移除 listing：标记 ended，之后不再改价。</li>
 * </ul>
 */
@Slf4j
class EbayRepricingRecovery {

    static final int MAX_TRANSIENT_RETRIES = 2;
    private static final long RETRY_BASE_DELAY_MS = 1000L;

    /** 一个待改价的 offer。knownQuantity 为改价前已知库存，null 表示未知。 */
    record Target(OfferPriceQuantity update, String styleId, String region, Integer knownQuantity) {
        boolean zeroing() {
            return update.price() == null && update.availableQuantity() != null
                    && update.availableQuantity() == 0;
        }

        /** 改完后这个尺码是否应当有货：有货才值得整组重新发布把它挂回 listing。 */
        boolean shouldBeInStock() {
            if (update.availableQuantity() != null) {
                return update.availableQuantity() > 0;
            }
            return knownQuantity == null || knownQuantity > 0;
        }
    }

    enum Kind {
        /** 已写入（可能经过重试或整组重新发布），note 里说明额外处理。 */
        UPDATED,
        /** 尺码库存为 0 已被 eBay 移出 listing，价格未写入。 */
        DETACHED_OUT_OF_STOCK,
        /** 整组都已无价，整组下架。 */
        GROUP_WITHDRAWN,
        /** eBay 因违规移除了 listing。 */
        REMOVED_BY_EBAY,
        FAILED
    }

    record Resolution(String offerId, Kind kind, String error, String note) {
        boolean failed() {
            return kind == Kind.FAILED;
        }
    }

    private final EbaySellApiClient client;
    private final EbayListingMapper listingMapper;
    private final EbayProperties properties;
    private final LongConsumer sleeper;

    EbayRepricingRecovery(EbaySellApiClient client, EbayListingMapper listingMapper,
                          EbayProperties properties) {
        this(client, listingMapper, properties, EbayRepricingRecovery::sleep);
    }

    EbayRepricingRecovery(EbaySellApiClient client, EbayListingMapper listingMapper,
                          EbayProperties properties, LongConsumer sleeper) {
        this.client = client;
        this.listingMapper = listingMapper;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    Round newRound() {
        return new Round();
    }

    /**
     * 一轮改价的共享状态：同一商品组在一轮内最多重新发布/下架一次，
     * 并发的批次之间也只做一次。线程安全。
     */
    final class Round {
        private final Map<String, CompletableFuture<String>> republished = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<String>> withdrawn = new ConcurrentHashMap<>();
        private final Set<String> endedGroups = ConcurrentHashMap.newKeySet();

        /** 改价并处理所有可恢复的错误；不抛异常，每个 target 都有一个结果。 */
        List<Resolution> apply(List<Target> targets) {
            Map<String, Resolution> resolved = new LinkedHashMap<>();
            List<Target> pending = new ArrayList<>();
            for (Target target : targets) {
                if (endedGroups.contains(groupId(target))) {
                    resolved.put(offerId(target), new Resolution(offerId(target), Kind.GROUP_WITHDRAWN,
                            null, "所在商品组本轮已下架，跳过"));
                } else {
                    pending.add(target);
                }
            }

            Map<String, OfferUpdateResult> results = updateWithSplit(pending);
            results = retryTransient(pending, results);

            List<Target> detached = new ArrayList<>();
            for (Target target : pending) {
                OfferUpdateResult result = results.get(offerId(target));
                if (result == null) {
                    resolved.put(offerId(target), failed(target, "eBay未返回该offer的处理结果"));
                } else if (result.success()) {
                    resolved.put(offerId(target), new Resolution(offerId(target), Kind.UPDATED, null, null));
                } else if (hasCode(result, "25013")) {
                    detached.add(target);
                } else if (hasCode(result, "25004") && target.zeroing()) {
                    resolved.put(offerId(target), withdrawGroup(target, result.error()));
                } else if (hasCode(result, "25097")) {
                    resolved.put(offerId(target), removedByEbay(target));
                } else {
                    resolved.put(offerId(target), failed(target, result.error()));
                }
            }
            for (Map.Entry<String, Resolution> entry : reattach(detached).entrySet()) {
                resolved.put(entry.getKey(), entry.getValue());
            }

            List<Resolution> ordered = new ArrayList<>(targets.size());
            for (Target target : targets) {
                ordered.add(resolved.get(offerId(target)));
            }
            return ordered;
        }

        private Map<String, Resolution> reattach(List<Target> detached) {
            Map<String, Resolution> resolved = new HashMap<>();
            List<Target> retry = new ArrayList<>();
            for (Target target : detached) {
                if (target.zeroing()) {
                    resolved.put(offerId(target), new Resolution(offerId(target), Kind.UPDATED, null,
                            "尺码已被eBay移出listing，库存已为0"));
                } else if (!target.shouldBeInStock()) {
                    resolved.put(offerId(target), new Resolution(offerId(target), Kind.DETACHED_OUT_OF_STOCK,
                            null, "尺码库存为0已被eBay移出listing，价格未写入（需重新上架该尺码）"));
                } else {
                    String error = republish(target);
                    if (error == null) {
                        retry.add(target);
                    } else {
                        resolved.put(offerId(target), failed(target,
                                "尺码已脱离listing，整组重新发布失败：" + error));
                    }
                }
            }
            if (retry.isEmpty()) {
                return resolved;
            }
            Map<String, OfferUpdateResult> results = retryTransient(retry, updateWithSplit(retry));
            for (Target target : retry) {
                OfferUpdateResult result = results.get(offerId(target));
                if (result != null && result.success()) {
                    resolved.put(offerId(target), new Resolution(offerId(target), Kind.UPDATED, null,
                            "尺码曾脱离listing，已整组重新发布挂回"));
                } else {
                    String error = result == null ? "eBay未返回该offer的处理结果" : result.error();
                    resolved.put(offerId(target), failed(target, "整组重新发布后仍失败：" + error));
                }
            }
            return resolved;
        }

        private String republish(Target target) {
            String groupKey;
            try {
                groupKey = groupKey(target);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            return once(republished, groupKey, () -> {
                log.warn("商品组{}（货号{}）有尺码脱离listing，整组重新发布", groupKey, target.styleId());
                client.publishOfferByInventoryItemGroup(groupKey, properties.getDefaultMarketplaceId());
            });
        }

        private Resolution withdrawGroup(Target target, String originalError) {
            String groupKey;
            try {
                groupKey = groupKey(target);
            } catch (IllegalArgumentException e) {
                return failed(target, originalError);
            }
            String error = once(withdrawn, groupKey, () -> {
                log.warn("商品组{}（货号{}）所有尺码均无得物价，整组下架", groupKey, target.styleId());
                client.withdrawOfferByInventoryItemGroup(groupKey, properties.getDefaultMarketplaceId());
                markGroupEnded(target);
            });
            if (error != null) {
                return failed(target, "整组无价，整组下架失败：" + error);
            }
            return new Resolution(offerId(target), Kind.GROUP_WITHDRAWN, null,
                    "整组均无得物价，已整组下架（需重新上传上架）");
        }

        private Resolution removedByEbay(Target target) {
            // 违规移除的是整个多尺码 listing，同组其它尺码也不必再改。
            markGroupEnded(target);
            return new Resolution(offerId(target), Kind.REMOVED_BY_EBAY, null,
                    "eBay因违反假货政策移除了该listing(25097)，已停止改价，请人工处理");
        }

        private void markGroupEnded(Target target) {
            endedGroups.add(groupId(target));
            try {
                listingMapper.markGroupEnded(target.styleId().trim(), regionCode(target));
            } catch (Exception e) {
                log.warn("eBay映射状态更新失败, styleId:{}", target.styleId(), e);
            }
        }

        /** 每个商品组每轮只执行一次，并发调用方等待同一个结果。返回 null 表示成功。 */
        private String once(Map<String, CompletableFuture<String>> done, String key, Runnable action) {
            CompletableFuture<String> mine = new CompletableFuture<>();
            CompletableFuture<String> existing = done.putIfAbsent(key, mine);
            if (existing != null) {
                return existing.join();
            }
            try {
                action.run();
                mine.complete(null);
            } catch (Exception e) {
                mine.complete(errorOf(e));
            }
            return mine.join();
        }
    }

    /** 整批请求被拒（4xx）时二分拆批，找出真正有问题的 offer，其余照常写入。 */
    private Map<String, OfferUpdateResult> updateWithSplit(List<Target> targets) {
        Map<String, OfferUpdateResult> results = new HashMap<>();
        if (targets.isEmpty()) {
            return results;
        }
        List<OfferPriceQuantity> updates = targets.stream().map(Target::update).toList();
        try {
            for (OfferUpdateResult result : client.bulkUpdatePriceQuantity(updates)) {
                results.putIfAbsent(result.offerId(), result);
            }
            return results;
        } catch (Exception e) {
            String error = errorOf(e);
            if (targets.size() > 1 && isClientError(e)) {
                int middle = targets.size() / 2;
                results.putAll(updateWithSplit(targets.subList(0, middle)));
                results.putAll(updateWithSplit(targets.subList(middle, targets.size())));
                return results;
            }
            int status = isClientError(e) ? 400 : 0;
            for (OfferPriceQuantity update : updates) {
                results.put(update.offerId(), new OfferUpdateResult(update.sku(), update.offerId(), status, error));
            }
            return results;
        }
    }

    /** 单个 offer 返回 5xx（eBay 内部临时错误）时退避重试。 */
    private Map<String, OfferUpdateResult> retryTransient(List<Target> targets,
                                                          Map<String, OfferUpdateResult> results) {
        Map<String, OfferUpdateResult> merged = new HashMap<>(results);
        for (int attempt = 1; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            List<Target> transientFailures = targets.stream().filter(t -> {
                OfferUpdateResult result = merged.get(offerId(t));
                return result != null && !result.success() && result.statusCode() >= 500;
            }).toList();
            if (transientFailures.isEmpty()) {
                break;
            }
            sleeper.accept(RETRY_BASE_DELAY_MS * attempt);
            merged.putAll(updateWithSplit(transientFailures));
        }
        return merged;
    }

    private String groupKey(Target target) {
        return EbayListingFactory.inventoryGroupKey(target.styleId(), EbayListingRegion.fromCode(target.region()));
    }

    private static String groupId(Target target) {
        String style = target.styleId() == null ? "" : target.styleId().trim().toUpperCase(java.util.Locale.ROOT);
        return style + "|" + regionCode(target);
    }

    private static String regionCode(Target target) {
        try {
            return EbayListingRegion.fromCode(target.region()).getCode();
        } catch (IllegalArgumentException e) {
            return EbayListingRegion.HK.getCode();
        }
    }

    private static String offerId(Target target) {
        return target.update().offerId();
    }

    private static Resolution failed(Target target, String error) {
        return new Resolution(offerId(target), Kind.FAILED, error, null);
    }

    private static boolean hasCode(OfferUpdateResult result, String code) {
        String error = result.error();
        return error != null && error.contains(code + ":");
    }

    private static boolean isClientError(Exception e) {
        String message = e.getMessage();
        return e instanceof EbayApiException && message != null && message.contains("(HTTP 4");
    }

    private static String errorOf(Exception e) {
        String message = e.getMessage();
        if (e instanceof EbayApiException && message != null && !message.isBlank()) {
            return message.length() <= 300 ? message : message.substring(0, 300);
        }
        return e.getClass().getSimpleName();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
