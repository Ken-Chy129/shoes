package cn.ken.shoes.model.dunk;

import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Data
public class DunkSearchRequest {

    private String func = "all";

    private String refId = "search";

    private Integer cardVersion = 2;

    private String itemConditions = "brand_new";

    private String sortKey;

    private Boolean isDiscounted;

    private Boolean isFirstHand;

    private Boolean isUnderRetail;

    private String stock = "any";

    private String keyword;

    private Integer page = 1;

    private Integer perPage = 40;
}
