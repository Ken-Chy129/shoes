package cn.ken.shoes.model.kickscrew;

import cn.ken.shoes.model.entity.KickScrewItemDO;
import lombok.Data;

import java.util.List;

@Data
public class KickScrewSearchResult {

    private List<KickScrewItemDO> hits;

    private Integer nbHits;

    private Integer page;

    private Integer hitPerPage;

}
