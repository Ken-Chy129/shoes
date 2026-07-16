package cn.ken.shoes.order;

import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.task.StockXOrderItemConverter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StockXOrderItemConverterTest {

    @Test
    void convertsStockXOrderFieldsIntoTaskItemData() {
        JSONObject order = JSON.parseObject("""
                {
                  "orderNumber": "04-UVW7RFTNQ",
                  "listingId": "0e01e186-aaaa-bbbb-cccc-1234567890ab",
                  "amount": "198",
                  "currencyCode": "USD",
                  "status": "COMPLETED",
                  "createdAt": "2026-07-06T03:04:05.000Z",
                  "product": {
                    "productName": "Nike Air Force 1 Low",
                    "styleId": "IO4489-601"
                  },
                  "variant": {
                    "variantId": "variant-1",
                    "variantValue": "9.5"
                  },
                  "payout": {
                    "salePrice": "198",
                    "totalPayout": "173.42",
                    "currencyCode": "USD"
                  }
                }
                """);

        TaskItemDO item = StockXOrderItemConverter.convert(88L, order);

        assertThat(item.getTaskId()).isEqualTo(88L);
        assertThat(item.getListingId()).isEqualTo("0e01e186-aaaa-bbbb-cccc-1234567890ab");
        assertThat(item.getProductId()).isEqualTo("variant-1");
        assertThat(item.getTitle()).isEqualTo("Nike Air Force 1 Low");
        assertThat(item.getStyleId()).isEqualTo("IO4489-601");
        assertThat(item.getSize()).isEqualTo("9.5");
        assertThat(item.getOrderNumber()).isEqualTo("04-UVW7RFTNQ");
        assertThat(item.getOrderStatus()).isEqualTo("销售完成");
        assertThat(item.getCurrencyCode()).isEqualTo("USD");
        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("198"));
        assertThat(item.getPayoutAmount()).isEqualByComparingTo(new BigDecimal("173.42"));
        assertThat(item.getSoldOn()).isNotNull();
        assertThat(item.getOperateResult()).isEqualTo("销售完成");
    }

    @Test
    void fallsBackToOrderAmountWhenPayoutDetailsAreMissing() {
        JSONObject order = JSON.parseObject("""
                {
                  "orderNumber": "04-PENDING",
                  "amount": "222",
                  "currencyCode": "USD",
                  "status": "PAYOUTPENDING",
                  "createdAt": "2026-07-07T03:04:05.000Z",
                  "product": {"productName": "Jordan 11 Retro", "styleId": "CT8012-047"},
                  "variant": {"variantId": "variant-2", "variantValue": "8.5"}
                }
                """);

        TaskItemDO item = StockXOrderItemConverter.convert(89L, order);

        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("222"));
        assertThat(item.getPayoutAmount()).isNull();
        assertThat(item.getOrderStatus()).isEqualTo("待付款");
    }
}
