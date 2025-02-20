package cn.ken.shoes.model.kickscrew;

import cn.ken.shoes.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class KickScrewItemRequest extends PageRequest {

    private List<Integer> releaseYears;

    private List<String> brands;

    private List<String> genders;

    private List<String> productTypes;

    private List<String> modelNumbers;

    private Boolean mustCrawl;
}

