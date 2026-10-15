package cn.ken.shoes.config;

import cn.ken.shoes.model.ebay.EbayListingRegion;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

@Data
@Component
@ConfigurationProperties(prefix = "ebay")
public class EbayProperties {

    public static final String TRADING_API_SCOPE =
            "https://api.ebay.com/oauth/api_scope";
    public static final String IDENTITY_READ_SCOPE =
            "https://api.ebay.com/oauth/api_scope/commerce.identity.readonly";
    public static final String FULFILLMENT_READ_SCOPE =
            "https://api.ebay.com/oauth/api_scope/sell.fulfillment.readonly";
    /** 只读广告活动（Promoted Listings 已设广告费率）。 */
    public static final String MARKETING_READ_SCOPE =
            "https://api.ebay.com/oauth/api_scope/sell.marketing.readonly";

    private String environment = "sandbox";
    private String clientId = "";
    private String clientSecret = "";
    private String ruName = "";
    private String scopes = "https://api.ebay.com/oauth/api_scope/sell.inventory "
            + "https://api.ebay.com/oauth/api_scope/sell.account";
    private String applicationScope = "https://api.ebay.com/oauth/api_scope";
    private long stateTtlSeconds = 600L;
    private int maxPendingStates = 100;
    private String defaultMerchantLocationKey = "shantou_chenghai";
    private String defaultFulfillmentPolicyId = "6246174000";
    private String defaultPaymentPolicyId = "6246171000";
    private String defaultReturnPolicyId = "6246169000";
    private String defaultMensCategoryId = "15709";
    private String defaultWomensCategoryId = "95672";
    private String defaultKidsCategoryId = "155202";
    private String defaultBabyCategoryId = "147285";
    private String defaultCategoryTreeId = "0";
    private String defaultMarketplaceId = "EBAY_US";
    private String defaultCurrency = "USD";
    private String defaultContentLanguage = "en-US";
    private String notificationEndpoint = "";
    private String notificationVerificationToken = "";
    /** 美区上架：发货地点与物流政策必须单独配置；付款/退货政策未配置时沿用默认值。 */
    private RegionSettings us = new RegionSettings();

    @Data
    public static class RegionSettings {
        private String merchantLocationKey = "";
        private String fulfillmentPolicyId = "";
        private String paymentPolicyId = "";
        private String returnPolicyId = "";
    }

    public record ListingSettings(String merchantLocationKey, String fulfillmentPolicyId,
                                  String paymentPolicyId, String returnPolicyId) {
    }

    public ListingSettings listingSettings(EbayListingRegion region) {
        if (region == null || region == EbayListingRegion.HK) {
            return new ListingSettings(defaultMerchantLocationKey, defaultFulfillmentPolicyId,
                    defaultPaymentPolicyId, defaultReturnPolicyId);
        }
        RegionSettings settings = us == null ? new RegionSettings() : us;
        return new ListingSettings(settings.getMerchantLocationKey(),
                settings.getFulfillmentPolicyId(),
                isPresent(settings.getPaymentPolicyId())
                        ? settings.getPaymentPolicyId() : defaultPaymentPolicyId,
                isPresent(settings.getReturnPolicyId())
                        ? settings.getReturnPolicyId() : defaultReturnPolicyId);
    }

    public boolean isRegionConfigured(EbayListingRegion region) {
        ListingSettings settings = listingSettings(region);
        return isPresent(settings.merchantLocationKey()) && isPresent(settings.fulfillmentPolicyId())
                && isPresent(settings.paymentPolicyId()) && isPresent(settings.returnPolicyId());
    }

    public boolean isSandbox() {
        return !"production".equalsIgnoreCase(environment);
    }

    public String getAuthorizationEndpoint() {
        return isSandbox()
                ? "https://auth.sandbox.ebay.com/oauth2/authorize"
                : "https://auth.ebay.com/oauth2/authorize";
    }

    public String getTokenEndpoint() {
        return isSandbox()
                ? "https://api.sandbox.ebay.com/identity/v1/oauth2/token"
                : "https://api.ebay.com/identity/v1/oauth2/token";
    }

    public String getInventoryApiEndpoint() {
        return apiRoot() + "/sell/inventory/v1/";
    }

    public String getAccountApiEndpoint() {
        return apiRoot() + "/sell/account/v1/";
    }

    public String getTaxonomyApiEndpoint() {
        return apiRoot() + "/commerce/taxonomy/v1/";
    }

    public String getNotificationApiEndpoint() {
        return apiRoot() + "/commerce/notification/v1/";
    }

    public String getFulfillmentApiEndpoint() {
        return apiRoot() + "/sell/fulfillment/v1/";
    }

    public String getMarketingApiEndpoint() {
        return apiRoot() + "/sell/marketing/v1/";
    }

    public String getTradingApiEndpoint() {
        return apiRoot() + "/ws/api.dll";
    }

    public String getIdentityApiEndpoint() {
        return isSandbox()
                ? "https://apiz.sandbox.ebay.com/commerce/identity/v1/user/"
                : "https://apiz.ebay.com/commerce/identity/v1/user/";
    }

    public String getScopes() {
        String configured = scopes == null ? "" : scopes.trim();
        LinkedHashSet<String> effective = Arrays.stream(configured.split("\\s+"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        effective.add(TRADING_API_SCOPE);
        effective.add(IDENTITY_READ_SCOPE);
        effective.add(FULFILLMENT_READ_SCOPE);
        effective.add(MARKETING_READ_SCOPE);
        return String.join(" ", effective);
    }

    public boolean isConfigured() {
        return isPresent(clientId) && isPresent(clientSecret) && isPresent(ruName);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private String apiRoot() {
        return isSandbox() ? "https://api.sandbox.ebay.com" : "https://api.ebay.com";
    }
}
