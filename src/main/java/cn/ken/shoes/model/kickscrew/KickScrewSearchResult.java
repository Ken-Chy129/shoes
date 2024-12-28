package cn.ken.shoes.model.kickscrew;

import lombok.Data;

import java.util.List;

@Data
public class KickScrewSearchResult {

    private List<KickScrewItem> hits;

    private Integer nbHits;

    private Integer page;

    private Integer hitPerPage;

}
