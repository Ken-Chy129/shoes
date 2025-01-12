package cn.ken.shoes.model.item;

import lombok.Data;

import java.util.List;

@Data
public class ItemRequest {

    private List<Integer> releaseYears;

    private List<String> brands;

    private List<String> genders;

    private Integer pageIndex;

    private Integer pageSize;
}
