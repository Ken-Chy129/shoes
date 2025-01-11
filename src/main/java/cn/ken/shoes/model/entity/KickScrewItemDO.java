package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kick_screw_item")
public class KickScrewItemDO {

    private String title;

    private String modelNo;

    private String brand;

    private String productType;

    private String image;

    private String gender;

    private String releaseYear;

    /**
     * 根据此值查询尺码价格
     */
    private String handle;

}
