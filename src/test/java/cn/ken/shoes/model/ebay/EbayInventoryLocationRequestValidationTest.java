package cn.ken.shoes.model.ebay;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EbayInventoryLocationRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsHongKongAddressWithoutPostalCode() {
        EbayInventoryLocationRequest request = validRequest();
        request.setCountry("HK");
        request.setPostalCode(null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void requiresPostalCodeOutsideHongKong() {
        EbayInventoryLocationRequest request = validRequest();
        request.setCountry("CN");
        request.setPostalCode(null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("postalCodeValid");
    }

    private EbayInventoryLocationRequest validRequest() {
        EbayInventoryLocationRequest request = new EbayInventoryLocationRequest();
        request.setMerchantLocationKey("hong_kong_mong_kok");
        request.setName("Hong Kong Warehouse");
        request.setAddressLine1("Room 2, 2/F, Dezan Centre, 80 Larch Street");
        request.setAddressLine2("Tai Kok Tsui, Mong Kok");
        request.setCity("Hong Kong");
        request.setStateOrProvince("Hong Kong");
        request.setPostalCode("518000");
        request.setCountry("HK");
        return request;
    }
}
