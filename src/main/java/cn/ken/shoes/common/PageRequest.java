package cn.ken.shoes.common;

import lombok.Data;

@Data
public class PageRequest {

    private int pageIndex = 1;

    private int pageSize = 10;

    public int getStartIndex() {
        return (pageIndex - 1) * pageSize;
    }
}
