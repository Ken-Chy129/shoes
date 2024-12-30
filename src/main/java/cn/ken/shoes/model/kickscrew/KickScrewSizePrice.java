package cn.ken.shoes.model.kickscrew;

import lombok.Data;

import java.util.Map;

@Data
public class KickScrewSizePrice {

    private String title;

    private Map<String, Object> price;
}
