package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayInventoryLocationRequest;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EbayListingService {

    private static final int MAX_IDEMPOTENT_WRITE_ATTEMPTS = 5;
    private static final Pattern SINGLE_LISTING_SKU = Pattern.compile("SKU:\\s*([A-Za-z0-9_-]+)");

    private final EbaySellApiClient apiClient;
    private final EbayProperties properties;
    private final EbayPictureService pictureService;
    private final LongConsumer retrySleeper;

    @Autowired
    public EbayListingService(EbaySellApiClient apiClient, EbayProperties properties,
                              EbayPictureService pictureService) {
        this(apiClient, properties, pictureService, EbayListingService::sleep);
    }

    EbayListingService(EbaySellApiClient apiClient, EbayProperties properties,
                       EbayPictureService pictureService, LongConsumer retrySleeper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.pictureService = pictureService;
        this.retrySleeper = retrySleeper;
    }

    public EbayListingResult publish(EbayListingRequest request) {
        List<String> hostedImageUrls = pictureService.hostImages(
                request.getImageUrls(), request.getSku());
        createOrReplaceInventoryItemWithRetry(request, hostedImageUrls);
        JSONObject payload = offerPayload(request);
        OfferSnapshot existing = findOffer(request.getSku(), request.getMarketplaceId());
        String offerId;
        String listingId;
        if (existing == null) {
            offerId = apiClient.createOffer(payload, request.getContentLanguage());
            listingId = publishOfferWithRepair(
                    offerId, request, hostedImageUrls, payload);
        } else {
            offerId = existing.offerId();
            updateOfferWithRetry(
                    offerId, payload, request.getContentLanguage());
            listingId = existing.published()
                    ? existing.listingId()
                    : publishOfferWithRepair(
                    offerId, request, hostedImageUrls, payload);
        }
        return new EbayListingResult(request.getSku(), offerId, listingId, properties.getEnvironment());
    }

    private String publishOfferWithRepair(String offerId, EbayListingRequest request,
                                          List<String> hostedImageUrls,
                                          JSONObject offerPayload) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return apiClient.publishOffer(offerId);
            } catch (EbayApiException e) {
                if (attempt == 3 || !isRetryablePublishFailure(e)) {
                    throw e;
                }
                createOrReplaceInventoryItemWithRetry(request, hostedImageUrls);
                updateOfferWithRetry(
                        offerId, offerPayload, request.getContentLanguage());
                retrySleeper.accept(750L * attempt);
            }
        }
        throw new IllegalStateException("eBay发布重试未返回结果");
    }

    private boolean isRetryablePublishFailure(EbayApiException error) {
        String message = error.getMessage();
        return message != null && (message.contains("25001:")
                || message.contains("25004:")
                || message.contains("25604:")
                || message.contains("HTTP 500"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("eBay发布重试被中断", e);
        }
    }

    public List<EbayListingResult> publishGroup(String inventoryItemGroupKey,
                                                List<EbayListingRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("多尺码商品至少需要一个尺码");
        }
        List<EbayListingRequest> variants = List.copyOf(requests);
        JSONObject existingGroup = apiClient.getInventoryItemGroup(inventoryItemGroupKey)
                .orElse(null);
        EbayListingRequest first = variants.getFirst();
        Set<String> incomingSkus = skusOf(variants);
        // 历史组里只保留仍在架的 SKU。已下架尺码的 offer 已经 ENDED，若继续留在
        // variantSKUs 里，这次发布会把早已下架的尺码一起重新挂出去。
        Map<String, OfferSnapshot> retainedGroupOffers = new LinkedHashMap<>();
        // 在架历史 SKU 实际使用的尺码值（读不到时为 null），后面既用来剔除
        // variesBy 里的残留值，也用来识别本次尺码是否和历史 SKU 撞车。
        Map<String, String> retainedSizeValues = new LinkedHashMap<>();
        Map<String, OfferSnapshot> existingOffers = new LinkedHashMap<>();
        if (existingGroup != null) {
            for (String sku : stringValues(existingGroup.getJSONArray("variantSKUs"))) {
                if (incomingSkus.contains(sku)) {
                    continue;
                }
                OfferSnapshot offer = findOffer(sku, first.getMarketplaceId());
                if (offer != null && offer.published()) {
                    retainedGroupOffers.put(sku, offer);
                } else {
                    log.info("商品组{}中的历史SKU {}已下架或未发布，本次不再带入", inventoryItemGroupKey, sku);
                }
            }
            if (!retainedGroupOffers.isEmpty()) {
                ExistingVariation existingVariation = existingVariation(existingGroup);
                for (String sku : retainedGroupOffers.keySet()) {
                    retainedSizeValues.put(sku, inventoryItemSizeValue(sku, existingVariation.name()));
                }
                // EU 码换算出的美码可能和组里已在架的 USM 码一样（EU38 与 USM5.5），
                // 同一尺码值在组里只能出现一次，这时直接更新那条在架 SKU。
                reuseSkusWithSameSize(variants, existingVariation.name(), retainedSizeValues);
                incomingSkus = skusOf(variants);
                for (String sku : incomingSkus) {
                    OfferSnapshot reused = retainedGroupOffers.remove(sku);
                    if (reused != null) {
                        existingOffers.put(sku, reused);
                        retainedSizeValues.remove(sku);
                    }
                }
            }
        }
        for (String sku : incomingSkus) {
            if (existingOffers.containsKey(sku)) {
                continue;
            }
            OfferSnapshot offer = findOffer(sku, first.getMarketplaceId());
            if (offer != null) {
                existingOffers.put(sku, offer);
            }
        }
        boolean anyLiveOffer = !retainedGroupOffers.isEmpty()
                || existingOffers.values().stream().anyMatch(OfferSnapshot::published);
        if (existingGroup != null && !anyLiveOffer) {
            // 整组都已下架：旧组只剩作废尺码，删掉后按全新商品处理。
            log.info("商品组{}已无在架SKU，删除旧组后重新创建", inventoryItemGroupKey);
            apiClient.deleteInventoryItemGroup(inventoryItemGroupKey);
            existingGroup = null;
        }
        // 单尺码也统一走商品组：先前单尺码直接发单品 listing，之后再补尺码时
        // eBay 会报 25704（SKU 已是单品 listing），同一货号就散成多个 listing。
        GroupAspects groupAspects = validateAndResolveGroupAspects(variants, existingGroup);
        List<String> hostedImageUrls = pictureService.hostImages(
                first.getImageUrls(), inventoryItemGroupKey);
        Set<String> allGroupSkus = new LinkedHashSet<>(retainedGroupOffers.keySet());
        allGroupSkus.addAll(incomingSkus);
        OfferSnapshot publishedGroupOffer = existingOffers.values().stream()
                .filter(OfferSnapshot::published)
                .findFirst()
                .orElseGet(() -> retainedGroupOffers.values().stream().findFirst().orElse(null));

        for (EbayListingRequest variant : variants) {
            createOrReplaceInventoryItemWithRetry(variant, hostedImageUrls);
        }
        JSONObject groupPayload = inventoryGroupPayload(
                variants, groupAspects, hostedImageUrls, existingGroup,
                allGroupSkus, retainedSizeValues);
        createOrReplaceInventoryItemGroupWithRetry(
                inventoryItemGroupKey, groupPayload, first.getContentLanguage());

        boolean listingAlreadyPublished = publishedGroupOffer != null;
        String existingListingId = publishedGroupOffer == null
                ? null : publishedGroupOffer.listingId();
        Map<String, String> offerIds = new LinkedHashMap<>();
        Map<String, String> listingIds = new LinkedHashMap<>();
        List<PendingOffer> pendingOffers = new ArrayList<>();
        for (EbayListingRequest variant : variants) {
            OfferSnapshot existing = existingOffers.get(variant.getSku());
            String offerId;
            if (existing == null) {
                offerId = apiClient.createOffer(
                        offerPayload(variant), variant.getContentLanguage());
                pendingOffers.add(new PendingOffer(variant.getSku(), offerId));
            } else {
                offerId = existing.offerId();
                updateOfferWithRetry(
                        offerId, offerPayload(variant), variant.getContentLanguage());
                if (existing.published()) {
                    listingIds.put(variant.getSku(), existing.listingId());
                } else {
                    pendingOffers.add(new PendingOffer(variant.getSku(), offerId));
                }
            }
            offerIds.put(variant.getSku(), offerId);
        }

        try {
            if (listingAlreadyPublished) {
                for (PendingOffer pending : pendingOffers) {
                    String listingId = apiClient.publishOffer(pending.offerId());
                    listingIds.put(pending.sku(), listingId);
                    if (existingListingId == null) {
                        existingListingId = listingId;
                    }
                }
            } else {
                existingListingId = publishGroupWithRepair(
                        inventoryItemGroupKey, groupPayload, first);
            }
        } catch (EbayApiException e) {
            if (!isSingleSkuListingConflict(e)) {
                throw e;
            }
            existingListingId = republishAfterWithdrawingSingleListings(
                    e, inventoryItemGroupKey, groupPayload, first, offerIds, allGroupSkus.size());
            listingIds.clear();
        }

        List<EbayListingResult> results = new ArrayList<>(variants.size());
        for (EbayListingRequest variant : variants) {
            results.add(new EbayListingResult(
                    variant.getSku(),
                    offerIds.get(variant.getSku()),
                    firstNonBlank(listingIds.get(variant.getSku()), existingListingId),
                    properties.getEnvironment()));
        }
        return List.copyOf(results);
    }

    private Set<String> skusOf(List<EbayListingRequest> variants) {
        return new LinkedHashSet<>(variants.stream()
                .map(EbayListingRequest::getSku)
                .toList());
    }

    /**
     * 本次提交的尺码若与组里某个在架 SKU 的尺码值相同，改为复用那个 SKU：
     * 后续会覆盖它的库存项并更新 offer 的价格/数量，而不是再建一个同码 SKU。
     */
    private void reuseSkusWithSameSize(List<EbayListingRequest> variants, String varyingName,
                                       Map<String, String> retainedSizeValues) {
        for (EbayListingRequest variant : variants) {
            List<String> value = effectiveAspects(variant).get(varyingName);
            if (value == null || value.size() != 1 || value.getFirst().isBlank()) {
                continue;
            }
            for (Map.Entry<String, String> retained : retainedSizeValues.entrySet()) {
                if (value.getFirst().equals(retained.getValue())
                        && !retained.getKey().equals(variant.getSku())) {
                    log.info("尺码{}已由在架SKU {}占用，本次SKU {}改为更新该SKU",
                            value.getFirst(), retained.getKey(), variant.getSku());
                    variant.setSku(retained.getKey());
                    break;
                }
            }
        }
    }

    /** 读取库存项实际使用的变体属性值；读不到或值不唯一时返回 null。 */
    private String inventoryItemSizeValue(String sku, String aspectName) {
        Optional<JSONObject> item;
        try {
            item = apiClient.getInventoryItem(sku);
        } catch (RuntimeException e) {
            log.warn("读取eBay库存项失败，保留其历史尺码, sku:{}", sku, e);
            return null;
        }
        if (item.isEmpty()) {
            return null;
        }
        List<String> value = aspectValues(item.get(), aspectName);
        return value.size() == 1 && !value.getFirst().isBlank() ? value.getFirst() : null;
    }

    /**
     * eBay 25704：组里某个 SKU 早先被当成单品 listing 发布过，不能再进多尺码
     * listing。把那条单品 offer 下架后整组重新发布，让所有尺码回到同一个 listing。
     */
    private String republishAfterWithdrawingSingleListings(EbayApiException error,
                                                           String inventoryItemGroupKey,
                                                           JSONObject groupPayload,
                                                           EbayListingRequest first,
                                                           Map<String, String> offerIds,
                                                           int maxAttempts) {
        EbayApiException current = error;
        for (int attempt = 0; attempt < Math.max(1, maxAttempts); attempt++) {
            String sku = conflictingSku(current);
            String offerId = sku == null ? null : offerIds.get(sku);
            if (offerId == null && sku != null) {
                OfferSnapshot offer = findOffer(sku, first.getMarketplaceId());
                offerId = offer == null ? null : offer.offerId();
            }
            if (offerId == null) {
                throw current;
            }
            log.warn("商品组{}中的SKU {}已是单品listing，下架该offer {}后整组重新发布",
                    inventoryItemGroupKey, sku, offerId);
            apiClient.withdrawOffer(offerId);
            try {
                return publishGroupWithRepair(inventoryItemGroupKey, groupPayload, first);
            } catch (EbayApiException e) {
                if (!isSingleSkuListingConflict(e)) {
                    throw e;
                }
                current = e;
            }
        }
        throw current;
    }

    private boolean isSingleSkuListingConflict(EbayApiException error) {
        String message = error.getMessage();
        return message != null && message.contains("25704:");
    }

    private String conflictingSku(EbayApiException error) {
        String message = error.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = SINGLE_LISTING_SKU.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 整组发布。eBay 偶发会把某个商品组的服务端状态弄坏，此后该组的整组发布
     * 永远只回 25001（内部错误），而组内数据本身完全合法——把组删掉再用同一个
     * key 和同样的 payload 重建即可恢复。这里对 25001 做一次这样的自愈重试。
     */
    private String publishGroupWithRepair(String inventoryItemGroupKey,
                                          JSONObject groupPayload,
                                          EbayListingRequest first) {
        try {
            return apiClient.publishOfferByInventoryItemGroup(
                    inventoryItemGroupKey, first.getMarketplaceId());
        } catch (EbayApiException e) {
            if (!isCorruptedGroupFailure(e)) {
                throw e;
            }
            log.warn("商品组{}整组发布返回25001，删除并重建该组后重试", inventoryItemGroupKey, e);
            apiClient.deleteInventoryItemGroup(inventoryItemGroupKey);
            createOrReplaceInventoryItemGroupWithRetry(
                    inventoryItemGroupKey, groupPayload, first.getContentLanguage());
            return apiClient.publishOfferByInventoryItemGroup(
                    inventoryItemGroupKey, first.getMarketplaceId());
        }
    }

    private boolean isCorruptedGroupFailure(EbayApiException error) {
        String message = error.getMessage();
        return message != null && message.contains("25001:");
    }

    private void createOrReplaceInventoryItemWithRetry(
            EbayListingRequest request, List<String> hostedImageUrls) {
        retryIdempotentWrite(() -> apiClient.createOrReplaceInventoryItem(
                request.getSku(), inventoryPayload(request, hostedImageUrls),
                request.getContentLanguage()));
    }

    private void createOrReplaceInventoryItemGroupWithRetry(
            String inventoryItemGroupKey, JSONObject payload, String contentLanguage) {
        retryIdempotentWrite(() -> apiClient.createOrReplaceInventoryItemGroup(
                inventoryItemGroupKey, payload, contentLanguage));
    }

    private void updateOfferWithRetry(
            String offerId, JSONObject payload, String contentLanguage) {
        retryIdempotentWrite(() -> apiClient.updateOffer(
                offerId, payload, contentLanguage));
    }

    private void retryIdempotentWrite(Runnable operation) {
        for (int attempt = 1; attempt <= MAX_IDEMPOTENT_WRITE_ATTEMPTS; attempt++) {
            try {
                operation.run();
                return;
            } catch (EbayApiException e) {
                if (attempt == MAX_IDEMPOTENT_WRITE_ATTEMPTS
                        || !isRetryablePublishFailure(e)) {
                    throw e;
                }
                retrySleeper.accept(750L * attempt);
            }
        }
    }

    public JSONObject getPrerequisites(String marketplaceId) {
        JSONObject result = new JSONObject(true);
        result.put("environment", properties.getEnvironment());
        result.put("marketplaceId", marketplaceId);
        result.put("locations", apiClient.getInventoryLocations());
        result.put("fulfillmentPolicies", apiClient.getFulfillmentPolicies(marketplaceId));
        result.put("paymentPolicies", apiClient.getPaymentPolicies(marketplaceId));
        result.put("returnPolicies", apiClient.getReturnPolicies(marketplaceId));
        return result;
    }

    public void createInventoryLocation(EbayInventoryLocationRequest request) {
        JSONObject address = new JSONObject(true);
        address.put("addressLine1", request.getAddressLine1());
        putIfPresent(address, "addressLine2", request.getAddressLine2());
        address.put("city", request.getCity());
        address.put("stateOrProvince", request.getStateOrProvince());
        putIfPresent(address, "postalCode", request.getPostalCode());
        address.put("country", request.getCountry());

        JSONObject payload = new JSONObject(true);
        payload.put("name", request.getName());
        payload.put("merchantLocationStatus", "ENABLED");
        payload.put("locationTypes", new JSONArray().fluentAdd("WAREHOUSE"));
        payload.put("location", new JSONObject(true).fluentPut("address", address));
        apiClient.createInventoryLocation(request.getMerchantLocationKey(), payload);
    }

    private JSONObject inventoryPayload(EbayListingRequest request,
                                        List<String> hostedImageUrls) {
        JSONObject shipAvailability = new JSONObject(true)
                .fluentPut("quantity", request.getQuantity());
        JSONObject availability = new JSONObject(true)
                .fluentPut("shipToLocationAvailability", shipAvailability);

        JSONObject product = new JSONObject(true);
        product.put("title", request.getTitle());
        product.put("description", request.getDescription());
        product.put("imageUrls", JSON.parseArray(JSON.toJSONString(hostedImageUrls)));
        JSONObject aspects = JSON.parseObject(JSON.toJSONString(effectiveAspects(request)));
        product.put("aspects", aspects);
        putIfPresent(product, "brand", request.getBrand());
        putIfPresent(product, "mpn", request.getMpn());

        JSONObject payload = new JSONObject(true);
        payload.put("availability", availability);
        payload.put("condition", request.getCondition());
        payload.put("product", product);
        return payload;
    }

    private JSONObject offerPayload(EbayListingRequest request) {
        JSONObject price = new JSONObject(true);
        price.put("currency", request.getCurrency());
        price.put("value", request.getPrice().toPlainString());

        JSONObject policies = new JSONObject(true);
        policies.put("fulfillmentPolicyId", request.getFulfillmentPolicyId());
        policies.put("paymentPolicyId", request.getPaymentPolicyId());
        policies.put("returnPolicyId", request.getReturnPolicyId());

        JSONObject payload = new JSONObject(true);
        payload.put("sku", request.getSku());
        payload.put("marketplaceId", request.getMarketplaceId());
        payload.put("format", "FIXED_PRICE");
        payload.put("listingDuration", "GTC");
        payload.put("availableQuantity", request.getQuantity());
        payload.put("categoryId", request.getCategoryId());
        payload.put("merchantLocationKey", request.getMerchantLocationKey());
        payload.put("listingDescription", request.getDescription());
        payload.put("includeCatalogProductDetails", false);
        payload.put("pricingSummary", new JSONObject(true).fluentPut("price", price));
        payload.put("listingPolicies", policies);
        return payload;
    }

    private JSONObject inventoryGroupPayload(List<EbayListingRequest> variants,
                                             GroupAspects groupAspects,
                                             List<String> hostedImageUrls,
                                             JSONObject existingGroup,
                                             Set<String> groupSkus,
                                             Map<String, String> retainedSizeValues) {
        EbayListingRequest first = variants.getFirst();
        JSONObject payload = new JSONObject(true);
        payload.put("title", first.getTitle());
        payload.put("description", first.getDescription());
        payload.put("imageUrls", JSON.parseArray(JSON.toJSONString(hostedImageUrls)));
        payload.put("variantSKUs", JSON.parseArray(JSON.toJSONString(groupSkus)));
        payload.put("aspects", JSON.parseObject(JSON.toJSONString(groupAspects.common())));
        JSONObject specification = new JSONObject(true);
        specification.put("name", groupAspects.varyingName());
        specification.put("values", JSON.parseArray(JSON.toJSONString(
                mergedVariationValues(existingGroup, groupAspects, retainedSizeValues))));
        JSONObject variesBy = new JSONObject(true);
        variesBy.put("specifications", new JSONArray().fluentAdd(specification));
        payload.put("variesBy", variesBy);
        return payload;
    }

    private GroupAspects validateAndResolveGroupAspects(List<EbayListingRequest> variants,
                                                        JSONObject existingGroup) {
        EbayListingRequest first = variants.getFirst();
        for (EbayListingRequest variant : variants.subList(1, variants.size())) {
            requireSame(first.getTitle(), variant.getTitle(), "标题");
            requireSame(first.getDescription(), variant.getDescription(), "描述");
            requireSame(first.getImageUrls(), variant.getImageUrls(), "图片");
            requireSame(first.getCondition(), variant.getCondition(), "商品状态");
            requireSame(first.getCategoryId(), variant.getCategoryId(), "类目");
            requireSame(first.getMarketplaceId(), variant.getMarketplaceId(), "站点");
            requireSame(first.getCurrency(), variant.getCurrency(), "币种");
            requireSame(first.getMerchantLocationKey(), variant.getMerchantLocationKey(), "发货地点");
            requireSame(first.getFulfillmentPolicyId(), variant.getFulfillmentPolicyId(), "物流政策");
            requireSame(first.getPaymentPolicyId(), variant.getPaymentPolicyId(), "付款政策");
            requireSame(first.getReturnPolicyId(), variant.getReturnPolicyId(), "退货政策");
            requireSame(first.getContentLanguage(), variant.getContentLanguage(), "语言");
        }

        if (variants.size() == 1) {
            String varyingName = existingGroup != null
                    ? existingVariation(existingGroup).name()
                    : singleVariantSizeAspect(first);
            List<String> value = effectiveAspects(first).get(varyingName);
            if (value == null || value.size() != 1 || value.getFirst().isBlank()) {
                throw new IllegalArgumentException("新增尺码缺少商品组使用的"
                        + varyingName + "属性");
            }
            Map<String, List<String>> common = new LinkedHashMap<>(effectiveAspects(first));
            common.remove(varyingName);
            return new GroupAspects(varyingName, List.of(value.getFirst()), common);
        }

        Map<String, List<String>> firstAspects = effectiveAspects(first);
        Set<String> aspectNames = new LinkedHashSet<>(firstAspects.keySet());
        variants.forEach(variant -> aspectNames.addAll(effectiveAspects(variant).keySet()));
        List<String> varyingNames = aspectNames.stream()
                .filter(name -> variants.stream()
                        .map(variant -> effectiveAspects(variant).get(name))
                        .distinct().count() > 1)
                .toList();
        if (varyingNames.size() != 1) {
            String detected = varyingNames.isEmpty()
                    ? "未识别到尺码属性"
                    : "检测到多个变化属性：" + String.join("、", varyingNames);
            throw new IllegalArgumentException(
                    "同一货号必须且只能按尺码生成变体（" + detected + "）");
        }
        String varyingName = varyingNames.getFirst();
        List<String> values = variants.stream()
                .map(variant -> effectiveAspects(variant).get(varyingName))
                .map(value -> {
                    if (value == null || value.size() != 1 || value.getFirst().isBlank()) {
                        throw new IllegalArgumentException("每个尺码变体必须有一个明确的尺码值");
                    }
                    return value.getFirst();
                })
                .distinct()
                .toList();
        if (values.size() != variants.size()) {
            throw new IllegalArgumentException("同一货号的尺码不能重复");
        }
        Map<String, List<String>> common = new LinkedHashMap<>();
        firstAspects.forEach((name, value) -> {
            if (!varyingName.equals(name)) {
                common.put(name, value);
            }
        });
        return new GroupAspects(varyingName, values, common);
    }

    /** 只有一个尺码且尚无商品组时，用请求里的尺码属性作为变体维度。 */
    private String singleVariantSizeAspect(EbayListingRequest request) {
        Map<String, List<String>> aspects = effectiveAspects(request);
        return aspects.keySet().stream()
                .filter(name -> isUsSizeAspect(name) || isEuSizeAspect(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "同一货号必须且只能按尺码生成变体（未识别到尺码属性）"));
    }

    private List<String> mergedVariationValues(JSONObject existingGroup,
                                               GroupAspects groupAspects,
                                               Map<String, String> retainedSizeValues) {
        if (existingGroup == null) {
            return List.copyOf(new LinkedHashSet<>(groupAspects.values()));
        }
        ExistingVariation existingVariation = existingVariation(existingGroup);
        if (!groupAspects.varyingName().equals(existingVariation.name())) {
            throw new IllegalArgumentException("已有商品组的变体属性为"
                    + existingVariation.name() + "，不能改为" + groupAspects.varyingName());
        }
        // 只保留仍被组内其它 SKU 使用的历史值。上一次失败的上架会把作废的
        // 尺码串留在 variesBy 里，累积下来会让后续上架撞上早已不用的值而报错
        // （eBay 25129 报的尺码常常和本次提交的对不上，就是撞了这些残留）。
        LinkedHashSet<String> values = new LinkedHashSet<>(
                variationValuesInUse(existingVariation, retainedSizeValues));
        values.addAll(groupAspects.values());
        return List.copyOf(values);
    }

    /**
     * 找出历史变体值里仍然有效的部分。
     *
     * <p>依据组内在架历史 SKU 实际在用的变体属性值，只有确认「组内没有任何
     * SKU 还在用」的历史值才会被剔除。有任何 SKU 的尺码没读到（网络抖动、
     * SKU 已删除）时一律保守保留，宁可多留也不误删别人的尺码。
     */
    private List<String> variationValuesInUse(ExistingVariation existingVariation,
                                              Map<String, String> retainedSizeValues) {
        if (existingVariation.values().isEmpty()) {
            return List.of();
        }
        if (retainedSizeValues.containsValue(null)) {
            return existingVariation.values();
        }
        Set<String> inUse = new LinkedHashSet<>(retainedSizeValues.values());
        return existingVariation.values().stream()
                .filter(inUse::contains)
                .toList();
    }

    private List<String> aspectValues(JSONObject inventoryItem, String aspectName) {
        JSONObject product = inventoryItem.getJSONObject("product");
        JSONObject aspects = product == null ? null : product.getJSONObject("aspects");
        return aspects == null ? List.of() : stringValues(aspects.getJSONArray(aspectName));
    }

    private ExistingVariation existingVariation(JSONObject existingGroup) {
        JSONObject variesBy = existingGroup == null
                ? null : existingGroup.getJSONObject("variesBy");
        JSONArray specifications = variesBy == null
                ? null : variesBy.getJSONArray("specifications");
        JSONObject specification = specifications == null || specifications.isEmpty()
                ? null : specifications.getJSONObject(0);
        String name = specification == null ? null : specification.getString("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("已有eBay商品组缺少变体属性");
        }
        return new ExistingVariation(
                name, stringValues(specification.getJSONArray("values")));
    }

    private List<String> stringValues(JSONArray values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                result.add(value.toString());
            }
        }
        return List.copyOf(result);
    }

    private OfferSnapshot findOffer(String sku, String marketplaceId) {
        return apiClient.getOffersBySku(sku).stream()
                .filter(Objects::nonNull)
                .filter(offer -> {
                    String offerMarketplace = offer.getString("marketplaceId");
                    return offerMarketplace == null
                            || marketplaceId.equalsIgnoreCase(offerMarketplace);
                })
                .map(this::offerSnapshot)
                .filter(Objects::nonNull)
                .sorted((left, right) -> Boolean.compare(
                        right.published(), left.published()))
                .findFirst()
                .orElse(null);
    }

    private OfferSnapshot offerSnapshot(JSONObject offer) {
        String offerId = offer.getString("offerId");
        if (offerId == null || offerId.isBlank()) {
            return null;
        }
        boolean published = isPublished(offer);
        String listingId = listingId(offer);
        if (published && listingId == null) {
            JSONObject detail = apiClient.getOffer(offerId);
            listingId = listingId(detail);
            published = isPublished(detail) || published;
        }
        return new OfferSnapshot(offerId, published, listingId);
    }

    /**
     * offer 是否还占着一个在售 listing。下架(withdraw)后的 offer 仍会带着旧的
     * listingId，但 listingStatus 已是 ENDED，不能再当成已发布去复用。
     */
    private boolean isPublished(JSONObject offer) {
        if (offer == null) {
            return false;
        }
        JSONObject listing = offer.getJSONObject("listing");
        String listingStatus = firstNonBlank(
                listing == null ? null : listing.getString("listingStatus"),
                offer.getString("listingStatus"));
        if (listingStatus != null) {
            return "ACTIVE".equalsIgnoreCase(listingStatus)
                    || "OUT_OF_STOCK".equalsIgnoreCase(listingStatus);
        }
        return "PUBLISHED".equalsIgnoreCase(offer.getString("status"))
                || listingId(offer) != null;
    }

    private String listingId(JSONObject offer) {
        if (offer == null) {
            return null;
        }
        JSONObject listing = offer.getJSONObject("listing");
        return firstNonBlank(
                offer.getString("listingId"),
                listing == null ? null : listing.getString("listingId"));
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private Map<String, List<String>> effectiveAspects(EbayListingRequest request) {
        Map<String, List<String>> aspects = new LinkedHashMap<>(request.getAspects());
        if (request.getBrand() != null && !request.getBrand().isBlank()) {
            aspects.put("Brand", List.of(request.getBrand().trim()));
        }
        // Excel continues to accept EU sizes, but taxonomy resolution may add
        // the converted US size required by eBay. Aspect names differ by
        // category (for example "US Shoe Size" vs "US Size"). Only one size
        // aspect may vary in an inventory item group, so keep the eBay-facing
        // US dimension and omit a duplicate EU dimension.
        boolean hasUsSize = aspects.keySet().stream().anyMatch(this::isUsSizeAspect);
        if (hasUsSize) {
            aspects.keySet().removeIf(this::isEuSizeAspect);
        }
        return aspects;
    }

    private boolean isUsSizeAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "usshoesize".equals(name) || "ussize".equals(name);
    }

    private boolean isEuSizeAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "eushoesize".equals(name) || "eusize".equals(name);
    }

    private String normalizedAspectName(String rawName) {
        return rawName == null ? ""
                : rawName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void requireSame(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("同一货号的" + label + "必须一致");
        }
    }

    private void putIfPresent(JSONObject target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private record GroupAspects(String varyingName, List<String> values,
                                Map<String, List<String>> common) {
    }

    private record ExistingVariation(String name, List<String> values) {
    }

    private record OfferSnapshot(String offerId, boolean published, String listingId) {
    }

    private record PendingOffer(String sku, String offerId) {
    }
}
