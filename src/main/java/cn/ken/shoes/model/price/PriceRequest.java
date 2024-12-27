package cn.ken.shoes.model.price;

import cn.ken.shoes.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PriceRequest extends PageRequest {

    private String modelNumber;

    private String name;

    private String brand;

    private Integer priceType;
}
