package cn.ken.shoes.model.ebay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class EbayListingRequest {

    @NotBlank
    @Size(max = 50)
    private String sku;

    @NotBlank
    @Size(max = 80)
    private String title;

    @NotBlank
    @Size(max = 100_000)
    private String description;

    @NotEmpty
    @Size(max = 12)
    private List<@NotBlank @Size(max = 2_000) String> imageUrls;

    @NotNull
    @Min(1)
    @Max(999_999)
    private Integer quantity;

    @NotBlank
    @Pattern(regexp = "[A-Z][A-Z0-9_]*")
    @Size(max = 50)
    private String condition;

    @NotBlank
    @Pattern(regexp = "[0-9]{1,20}")
    private String categoryId;

    @NotBlank
    @Pattern(regexp = "EBAY_[A-Z0-9_]+")
    @Size(max = 40)
    private String marketplaceId;

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}")
    private String currency;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal price;

    @NotBlank
    @Size(max = 36)
    private String merchantLocationKey;

    @NotBlank
    @Size(max = 64)
    private String fulfillmentPolicyId;

    @NotBlank
    @Size(max = 64)
    private String paymentPolicyId;

    @NotBlank
    @Size(max = 64)
    private String returnPolicyId;

    @Size(max = 65)
    private String brand;

    @Size(max = 65)
    private String mpn;

    @NotEmpty
    @Size(max = 50)
    @Valid
    private Map<@NotBlank @Size(max = 65) String,
            @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 65) String>> aspects;

    @NotBlank
    @Pattern(regexp = "[a-z]{2}-[A-Z]{2}")
    private String contentLanguage = "en-US";
}
