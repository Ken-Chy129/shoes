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

import java.net.URI;
import java.net.URISyntaxException;

@Service
public class EbayListingService {

    private final EbaySellApiClient apiClient;
    private final EbayProperties properties;

    public EbayListingService(EbaySellApiClient apiClient, EbayProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
    }

    public EbayListingResult publish(EbayListingRequest request) {
        validateImageUrls(request);
        apiClient.createOrReplaceInventoryItem(
                request.getSku(), inventoryPayload(request), request.getContentLanguage());
        String offerId = apiClient.createOffer(offerPayload(request), request.getContentLanguage());
        String listingId = apiClient.publishOffer(offerId);
        return new EbayListingResult(request.getSku(), offerId, listingId, properties.getEnvironment());
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
        address.put("postalCode", request.getPostalCode());
        address.put("country", request.getCountry());

        JSONObject payload = new JSONObject(true);
        payload.put("name", request.getName());
        payload.put("merchantLocationStatus", "ENABLED");
        payload.put("locationTypes", new JSONArray().fluentAdd("WAREHOUSE"));
        payload.put("location", new JSONObject(true).fluentPut("address", address));
        apiClient.createInventoryLocation(request.getMerchantLocationKey(), payload);
    }

    private JSONObject inventoryPayload(EbayListingRequest request) {
        JSONObject shipAvailability = new JSONObject(true)
                .fluentPut("quantity", request.getQuantity());
        JSONObject availability = new JSONObject(true)
                .fluentPut("shipToLocationAvailability", shipAvailability);

        JSONObject product = new JSONObject(true);
        product.put("title", request.getTitle());
        product.put("description", request.getDescription());
        product.put("imageUrls", JSON.parseArray(JSON.toJSONString(request.getImageUrls())));
        product.put("aspects", JSON.parseObject(JSON.toJSONString(request.getAspects())));
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

    private void validateImageUrls(EbayListingRequest request) {
        if (request == null || request.getImageUrls() == null) {
            throw new IllegalArgumentException("at least one image URL is required");
        }
        for (String imageUrl : request.getImageUrls()) {
            try {
                URI uri = new URI(imageUrl);
                String scheme = uri.getScheme();
                if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("image URL must be a public HTTP(S) URL");
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("image URL is invalid", e);
            }
        }
    }

    private void putIfPresent(JSONObject target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
