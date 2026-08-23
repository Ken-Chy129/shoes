package cn.ken.shoes.model.ebay;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EbayListingRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsCompleteFixedPriceListing() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @Test
    void rejectsUnsafeOrOutOfRangeFields() {
        EbayListingRequest request = validRequest();
        request.setSku(" ");
        request.setTitle("x".repeat(81));
        request.setQuantity(0);
        request.setPrice(BigDecimal.ZERO);
        request.setMarketplaceId("https://evil.example");
        request.setCurrency("usd");
        request.setCategoryId("not-a-category");
        request.setImageUrls(List.of());

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("sku", "title", "quantity", "price", "marketplaceId",
                        "currency", "categoryId", "imageUrls");
    }

    private EbayListingRequest validRequest() {
        EbayListingRequest request = new EbayListingRequest();
        request.setSku("sku-1");
        request.setTitle("Test Sneaker");
        request.setDescription("Description");
        request.setImageUrls(List.of("https://example.com/image.jpg"));
        request.setQuantity(1);
        request.setCondition("NEW");
        request.setCategoryId("15709");
        request.setMarketplaceId("EBAY_US");
        request.setCurrency("USD");
        request.setPrice(new BigDecimal("99.00"));
        request.setMerchantLocationKey("shenzhen-main");
        request.setFulfillmentPolicyId("f-1");
        request.setPaymentPolicyId("p-1");
        request.setReturnPolicyId("r-1");
        request.setAspects(Map.of("US Shoe Size", List.of("9")));
        return request;
    }
}
