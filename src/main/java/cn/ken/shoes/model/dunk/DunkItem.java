package cn.ken.shoes.model.dunk;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Data
public class DunkItem {

    private String modelNo;

    private String displayCardPattern;

    private String title;

    private String link;

    private String imageUrl;

    private Boolean hasNewMark;

    private Integer salePrice;

    private JSONObject supershipLog;

    private String brandId;

    private String categoryId;

    private String itemId;

}