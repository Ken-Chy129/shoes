package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** eBay 盈利改价特殊运费：货号或物品编号 + 尺码（空为全尺码）→ AG 运费。 */
@Data
@TableName("ebay_special_freight")
public class EbaySpecialFreightDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String target;
    private String euSize;
    private BigDecimal freight;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date gmtModified;
}
