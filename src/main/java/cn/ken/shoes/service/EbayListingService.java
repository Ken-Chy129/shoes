package cn.ken.shoes.service;

import cn.ken.shoes.client.EbaySellApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayInventoryLocationRequest;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class EbayListingService {

    private final EbaySellApiClient apiClient;
    private final EbayProperties properties;
    private final EbayPictureService pictureService;

    public EbayListingService(EbaySellApiClient apiClient, EbayProperties properties,
                              EbayPictureService pictureService) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.pictureService = pictureService;
    }

    public EbayListingResult publish(EbayListingRequest request) {
        List<String> hostedImageUrls = pictureService.hostImages(
                request.getImageUrls(), request.getSku());
        apiClient.createOrReplaceInventoryItem(
                request.getSku(), inventoryPayload(request, hostedImageUrls),
                request.getContentLanguage());
        JSONObject payload = offerPayload(request);
        OfferSnapshot existing = findOffer(request.getSku(), request.getMarketplaceId());
        String offerId;
        String listingId;
        if (existing == null) {
            offerId = apiClient.createOffer(payload, request.getContentLanguage());
            listingId = apiClient.publishOffer(offerId);
        } else {
            offerId = existing.offerId();
            apiClient.updateOffer(offerId, payload, request.getContentLanguage());
            listingId = existing.published()
                    ? existing.listingId()
                    : apiClient.publishOffer(offerId);
        }
        return new EbayListingResult(request.getSku(), offerId, listingId, properties.getEnvironment());
    }

    public List<EbayListingResult> publishGroup(String inventoryItemGroupKey,
                                                List<EbayListingRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("多尺码商品至少需要一个尺码");
        }
        List<EbayListingRequest> variants = List.copyOf(requests);
        JSONObject existingGroup = apiClient.getInventoryItemGroup(inventoryItemGroupKey)
                .orElse(null);
        if (variants.size() == 1 && existingGroup == null) {
            return List.of(publish(variants.getFirst()));
        }
        GroupAspects groupAspects = validateAndResolveGroupAspects(variants, existingGroup);
        EbayListingRequest first = variants.getFirst();
        List<String> hostedImageUrls = pictureService.hostImages(
                first.getImageUrls(), inventoryItemGroupKey);
        Set<String> allGroupSkus = mergedGroupSkus(existingGroup, variants);
        Set<String> incomingSkus = new LinkedHashSet<>(variants.stream()
                .map(EbayListingRequest::getSku)
                .toList());
        Map<String, OfferSnapshot> existingOffers = new LinkedHashMap<>();
        for (String sku : incomingSkus) {
            OfferSnapshot offer = findOffer(sku, first.getMarketplaceId());
            if (offer != null) {
                existingOffers.put(sku, offer);
            }
        }
        OfferSnapshot publishedGroupOffer = existingOffers.values().stream()
                .filter(OfferSnapshot::published)
                .findFirst()
                .orElse(null);
        if (publishedGroupOffer == null) {
            for (String sku : allGroupSkus) {
                if (incomingSkus.contains(sku)) {
                    continue;
                }
                OfferSnapshot offer = findOffer(sku, first.getMarketplaceId());
                if (offer != null && offer.published()) {
                    publishedGroupOffer = offer;
                    break;
                }
            }
        }

        for (EbayListingRequest variant : variants) {
            apiClient.createOrReplaceInventoryItem(
                    variant.getSku(), inventoryPayload(variant, hostedImageUrls),
                    variant.getContentLanguage());
        }
        apiClient.createOrReplaceInventoryItemGroup(
                inventoryItemGroupKey,
                inventoryGroupPayload(
                        variants, groupAspects, hostedImageUrls, existingGroup),
                first.getContentLanguage());

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
                apiClient.updateOffer(
                        offerId, offerPayload(variant), variant.getContentLanguage());
                if (existing.published()) {
                    listingIds.put(variant.getSku(), existing.listingId());
                } else {
                    pendingOffers.add(new PendingOffer(variant.getSku(), offerId));
                }
            }
            offerIds.put(variant.getSku(), offerId);
        }

        if (listingAlreadyPublished) {
            for (PendingOffer pending : pendingOffers) {
                String listingId = apiClient.publishOffer(pending.offerId());
                listingIds.put(pending.sku(), listingId);
                if (existingListingId == null) {
                    existingListingId = listingId;
                }
            }
        } else {
            existingListingId = apiClient.publishOfferByInventoryItemGroup(
                    inventoryItemGroupKey, first.getMarketplaceId());
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
                                             JSONObject existingGroup) {
        EbayListingRequest first = variants.getFirst();
        JSONObject payload = new JSONObject(true);
        payload.put("title", first.getTitle());
        payload.put("description", first.getDescription());
        payload.put("imageUrls", JSON.parseArray(JSON.toJSONString(hostedImageUrls)));
        payload.put("variantSKUs", JSON.parseArray(JSON.toJSONString(
                mergedGroupSkus(existingGroup, variants))));
        payload.put("aspects", JSON.parseObject(JSON.toJSONString(groupAspects.common())));
        JSONObject specification = new JSONObject(true);
        specification.put("name", groupAspects.varyingName());
        specification.put("values", JSON.parseArray(JSON.toJSONString(
                mergedVariationValues(existingGroup, groupAspects))));
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
            ExistingVariation existingVariation = existingVariation(existingGroup);
            List<String> value = effectiveAspects(first).get(existingVariation.name());
            if (value == null || value.size() != 1 || value.getFirst().isBlank()) {
                throw new IllegalArgumentException("新增尺码缺少商品组使用的"
                        + existingVariation.name() + "属性");
            }
            Map<String, List<String>> common = new LinkedHashMap<>(effectiveAspects(first));
            common.remove(existingVariation.name());
            return new GroupAspects(
                    existingVariation.name(), List.of(value.getFirst()), common);
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

    private Set<String> mergedGroupSkus(JSONObject existingGroup,
                                        List<EbayListingRequest> variants) {
        Set<String> skus = new LinkedHashSet<>();
        if (existingGroup != null) {
            skus.addAll(stringValues(existingGroup.getJSONArray("variantSKUs")));
        }
        variants.stream().map(EbayListingRequest::getSku).forEach(skus::add);
        return skus;
    }

    private List<String> mergedVariationValues(JSONObject existingGroup,
                                               GroupAspects groupAspects) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (existingGroup != null) {
            ExistingVariation existingVariation = existingVariation(existingGroup);
            if (!groupAspects.varyingName().equals(existingVariation.name())) {
                throw new IllegalArgumentException("已有商品组的变体属性为"
                        + existingVariation.name() + "，不能改为" + groupAspects.varyingName());
            }
            values.addAll(existingVariation.values());
        }
        values.addAll(groupAspects.values());
        return List.copyOf(values);
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
        boolean published = "PUBLISHED".equalsIgnoreCase(offer.getString("status"))
                || "ACTIVE".equalsIgnoreCase(offer.getString("listingStatus"))
                || listingId(offer) != null;
        String listingId = listingId(offer);
        if (published && listingId == null) {
            JSONObject detail = apiClient.getOffer(offerId);
            listingId = listingId(detail);
            published = published
                    || "PUBLISHED".equalsIgnoreCase(detail.getString("status"))
                    || "ACTIVE".equalsIgnoreCase(detail.getString("listingStatus"));
        }
        return new OfferSnapshot(offerId, published, listingId);
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
