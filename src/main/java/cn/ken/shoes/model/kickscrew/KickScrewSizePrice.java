package cn.ken.shoes.model.kickscrew;

import lombok.Data;

import java.util.Map;

@Data
public class KickScrewSizePrice {

    private boolean availableForSale;

    private String title;

    private Map<String, String> price;
}
