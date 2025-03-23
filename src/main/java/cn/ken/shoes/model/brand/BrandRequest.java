package cn.ken.shoes.model.brand;

import lombok.Data;

@Data
public class BrandRequest {

    private String name;

    private Boolean needCrawl;

    private String platform;

    private Integer pageIndex = 1;

    private Integer pageSize = 10;

    public Integer getStartIndex() {
        return (pageIndex - 1) * pageSize;
    }
}
