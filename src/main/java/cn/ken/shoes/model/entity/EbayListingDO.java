package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * eBay 在售商品与本地货号/尺码的长期映射，按卖家 SKU 唯一。
 * 改价和下架以此为准，任务明细只作为执行审计。
 */
@Data
@TableName("ebay_listing")
public class EbayListingDO {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ENDED = "ended";

    @TableId(value = "sku", type = IdType.INPUT)
    private String sku;
    private String offerId;
    private String listingId;
    private String styleId;
    private String size;
    private String euSize;
    private String title;
    private String brand;
    private BigDecimal price;
    private Integer quantity;
    private String status;
    private Long sourceTaskId;
    private Date gmtCreate;
    private Date gmtModified;
}
