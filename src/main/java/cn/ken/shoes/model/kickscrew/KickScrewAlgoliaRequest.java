package cn.ken.shoes.model.kickscrew;

import lombok.Data;

import java.util.List;

@Data
public class KickScrewAlgoliaRequest {

    private Integer pageIndex;

    private Integer pageSize;

    private List<Integer> releaseYears;

    private List<String> brands;

    private List<String> genders;

    private List<String> productTypes;
}
