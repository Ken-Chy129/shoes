package cn.ken.shoes.model.ebay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EbayInventoryLocationRequest {

    @NotBlank
    @Size(max = 36)
    @Pattern(regexp = "[A-Za-z0-9_-]+")
    private String merchantLocationKey;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String addressLine1;

    @Size(max = 100)
    private String addressLine2;

    @NotBlank
    @Size(max = 100)
    private String city;

    @NotBlank
    @Size(max = 100)
    private String stateOrProvince;

    @NotBlank
    @Size(max = 20)
    private String postalCode;

    @NotBlank
    @Pattern(regexp = "[A-Z]{2}")
    private String country;
}
