package cn.ken.shoes.model.order;

import lombok.Data;

@Data
public class OrderRequest {

    private Integer page = 1;

    private Long dateTo;

    private Long dateFrom;

    private String status;
}
