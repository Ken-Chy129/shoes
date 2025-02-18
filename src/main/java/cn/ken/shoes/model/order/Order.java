package cn.ken.shoes.model.order;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class Order {

    private Long order_id;

    private String order_number;

    private String stock_id;

    private String recipient_name;

    private String address_line1;

    private String address_line2;

    private String city;

    private String state_province;

    private String country;

    private String mobile;

    private Size size;

    private Boolean on_hold;

    private String brand;

    private Integer price;

    private String currency;

    private String status;

    private BigDecimal service_fee;

    private BigDecimal operation_fee;

    private BigDecimal income;

    private String customer_order_reference;

    private Date created_at;

    private String ext_ref;

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
    private static class Payout {

        private Long payout_id;

        private String status;
    }
}
