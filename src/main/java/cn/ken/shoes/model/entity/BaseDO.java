package cn.ken.shoes.model.entity;

import lombok.Data;

import java.util.Date;

@Data
public class BaseDO {

    private Long id;

    private Date gmtCreate;

    private Date gmtModified;

    private Integer version;
}
