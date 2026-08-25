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
        String offerId = apiClient.createOffer(offerPayload(request), request.getContentLanguage());
        String listingId = apiClient.publishOffer(offerId);
        return new EbayListingResult(request.getSku(), offerId, listingId, properties.getEnvironment());
    }

    public List<EbayListingResult> publishGroup(String inventoryItemGroupKey,
                                                List<EbayListingRequest> requests) {
        if (requests == null || requests.size() < 2) {
            throw new IllegalArgumentException("多尺码商品至少需要两个尺码");
        }
        List<EbayListingRequest> variants = List.copyOf(requests);
        GroupAspects groupAspects = validateAndResolveGroupAspects(variants);
        EbayListingRequest first = variants.getFirst();
        List<String> hostedImageUrls = pictureService.hostImages(
                first.getImageUrls(), inventoryItemGroupKey);

        for (EbayListingRequest variant : variants) {
            apiClient.createOrReplaceInventoryItem(
                    variant.getSku(), inventoryPayload(variant, hostedImageUrls),
                    variant.getContentLanguage());
        }
        apiClient.createOrReplaceInventoryItemGroup(
                inventoryItemGroupKey,
                inventoryGroupPayload(variants, groupAspects, hostedImageUrls),
                first.getContentLanguage());

        List<String> offerIds = new ArrayList<>(variants.size());
        for (EbayListingRequest variant : variants) {
            offerIds.add(apiClient.createOffer(
                    offerPayload(variant), variant.getContentLanguage()));
        }
        String listingId = apiClient.publishOfferByInventoryItemGroup(
                inventoryItemGroupKey, first.getMarketplaceId());
        List<EbayListingResult> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            results.add(new EbayListingResult(
                    variants.get(i).getSku(), offerIds.get(i), listingId, properties.getEnvironment()));
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
                                             List<String> hostedImageUrls) {
        EbayListingRequest first = variants.getFirst();
        JSONObject payload = new JSONObject(true);
        payload.put("title", first.getTitle());
        payload.put("description", first.getDescription());
        payload.put("imageUrls", JSON.parseArray(JSON.toJSONString(hostedImageUrls)));
        payload.put("variantSKUs", JSON.parseArray(JSON.toJSONString(variants.stream()
                .map(EbayListingRequest::getSku).toList())));
        payload.put("aspects", JSON.parseObject(JSON.toJSONString(groupAspects.common())));
        JSONObject specification = new JSONObject(true);
        specification.put("name", groupAspects.varyingName());
        specification.put("values", JSON.parseArray(JSON.toJSONString(groupAspects.values())));
        JSONObject variesBy = new JSONObject(true);
        variesBy.put("specifications", new JSONArray().fluentAdd(specification));
        payload.put("variesBy", variesBy);
        return payload;
    }

    private GroupAspects validateAndResolveGroupAspects(List<EbayListingRequest> variants) {
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

        Map<String, List<String>> firstAspects = effectiveAspects(first);
        Set<String> aspectNames = new LinkedHashSet<>(firstAspects.keySet());
        variants.forEach(variant -> aspectNames.addAll(effectiveAspects(variant).keySet()));
        List<String> varyingNames = aspectNames.stream()
                .filter(name -> variants.stream()
                        .map(variant -> effectiveAspects(variant).get(name))
                        .distinct().count() > 1)
                .toList();
        if (varyingNames.size() != 1) {
            throw new IllegalArgumentException("同一货号的尺码变体必须且只能有一个不同属性");
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

    private Map<String, List<String>> effectiveAspects(EbayListingRequest request) {
        Map<String, List<String>> aspects = new LinkedHashMap<>(request.getAspects());
        if (request.getBrand() != null && !request.getBrand().isBlank()) {
            aspects.put("Brand", List.of(request.getBrand().trim()));
        }
        return aspects;
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
}
