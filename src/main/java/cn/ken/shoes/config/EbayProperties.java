package cn.ken.shoes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ebay")
public class EbayProperties {

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
    private String defaultCategoryTreeId = "0";
    private String defaultMarketplaceId = "EBAY_US";
    private String defaultCurrency = "USD";
    private String defaultContentLanguage = "en-US";

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
