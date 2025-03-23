package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("brand")
public class BrandDO {

    /**
     * 品牌名称
     */
    private String name;

    /**
     * 商品总数
     */
    private Integer total;

    /**
     * 目标爬取数量
     */
    private Integer crawlCnt;

    /**
     * 是否爬取
     */
    private Boolean needCrawl;

    /**
     * 平台
     */
    private String platform;

}
