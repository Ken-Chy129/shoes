package cn.ken.shoes.model.shoes;

import cn.ken.shoes.common.PageRequest;
import lombok.Data;

@Data
public class ShoesRequest extends PageRequest {

    private String modelNumber;

    private String name;

    private String brand;

    private String releaseYear;

}
