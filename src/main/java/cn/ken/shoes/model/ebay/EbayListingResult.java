package cn.ken.shoes.model.ebay;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EbayListingResult {

    private String sku;
    private String offerId;
    private String listingId;
    private String environment;
}
