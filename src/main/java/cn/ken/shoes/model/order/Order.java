package cn.ken.shoes.model.order;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class Order {

    @ExcelProperty("订单id")
    private Long orderId;

    @ExcelProperty("订单号")
    private String orderNumber;

    @ExcelProperty("库存id")
    private String stockId;

    @ExcelIgnore
    private String recipientName;

    @ExcelProperty("一级地址")
    private String addressLine1;

    @ExcelProperty("二级地址")
    private String addressLine2;

    @ExcelProperty("城市")
    private String city;

    @ExcelProperty("州/省")
    private String stateProvince;

    @ExcelProperty("国家")
    private String country;

    @ExcelProperty("手机号码")
    private String mobile;

    @ExcelIgnore
    private Size size;

    @ExcelProperty("是否挂起")
    private Boolean on_hold;

    @ExcelProperty("品牌")
    private String brand;

    @ExcelProperty("货号")
    private String modelNo;

    @ExcelProperty("欧码")
    private String euSize;

    @ExcelProperty("价格")
    private Integer price;

    @ExcelProperty("货币")
    private String currency;

    @ExcelProperty("状态")
    private String status;

    @ExcelProperty("取消原因")
    private String cancelReason;

    @ExcelProperty("订单支付状态")
    private String payoutStatus;

    @ExcelProperty("服务费用")
    private BigDecimal serviceFee;

    @ExcelProperty("操作费用")
    private BigDecimal operationFee;

    @ExcelProperty("收入")
    private BigDecimal income;

    @ExcelProperty("客户订单参考")
    private String customerOrderReference;

    @ExcelProperty("创建时间")
    private Date createdAt;

    @ExcelProperty("扩展信息")
    private String extRef;

    @ExcelIgnore
    private Payout payout;

    @Data
    public static class Size {

        private String displayValue;

        private Integer displayOrder;

        private String US;

        private String UK;

        private String EU;
    }

    @Data
    public static class Payout {

        private Long payout_id;

        private String status;
    }
}
