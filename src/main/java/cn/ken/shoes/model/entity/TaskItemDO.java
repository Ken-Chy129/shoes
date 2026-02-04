package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("task_item")
public class TaskItemDO {

    private Long id;

    private Long taskId;

    private String title;

    private String listingId;

    private String productId;

    private String styleId;

    private String size;

    private String euSize;

    private BigDecimal currentPrice;

    private BigDecimal lowestPrice;

    private BigDecimal poisonPrice;

    private BigDecimal poison35Price;

    private BigDecimal profit35;

    private BigDecimal profitRate35;

    private String operateResult;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date operateTime;
}
