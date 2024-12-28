package cn.ken.shoes.model.kickscrew;

import lombok.Data;

import java.util.Map;

@Data
public class KickScrewCategory {

    private Map<String, Integer> brand;

    private Map<String, Integer> gender;

    private Map<String, Integer> lowestPrice;

    private Map<String, Integer> mainColor;

    private Map<String, Integer> productType;

    private Map<String, Integer> releaseYear;

    private Map<String, Integer> size;

}
