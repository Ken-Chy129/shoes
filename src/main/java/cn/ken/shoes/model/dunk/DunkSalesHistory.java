package cn.ken.shoes.model.dunk;

import lombok.Data;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Data
public class DunkSalesHistory {

    private String modelNo;

    private Integer sizeId;

    private String size;

    private Integer price;

    private String date;
}
