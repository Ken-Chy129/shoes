package cn.ken.shoes.model.catalog;

import cn.ken.shoes.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProductCatalogPageRequest extends PageRequest {

    private String modelNo;
    private String brand;
    private String source;
}
