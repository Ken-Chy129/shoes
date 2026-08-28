package cn.ken.shoes.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EbayPropertiesTest {

    @Test
    void usesTheEbayApiZoneForProductionIdentityRequests() {
        EbayProperties properties = new EbayProperties();
        properties.setEnvironment("production");

        assertThat(properties.getIdentityApiEndpoint())
                .isEqualTo("https://apiz.ebay.com/commerce/identity/v1/user/");
    }

    @Test
    void usesTheEbayApiZoneForSandboxIdentityRequests() {
        EbayProperties properties = new EbayProperties();
        properties.setEnvironment("sandbox");

        assertThat(properties.getIdentityApiEndpoint())
                .isEqualTo("https://apiz.sandbox.ebay.com/commerce/identity/v1/user/");
    }
}
