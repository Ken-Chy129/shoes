package cn.ken.shoes.order;

import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.task.StockXOrderItemConverter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StockXOrderItemConverterTest {

    @Test
    void convertsPendingAskIntoOrderFieldsAndShippingDeadline() {
        JSONObject ask = JSON.parseObject("""
                {
                  "id": "14958362-pending-ask",
                  "amount": 648,
                  "currentCurrency": "USD",
                  "soldOn": "2026-07-16T03:04:05.000Z",
                  "dateToShipBy": "2026-07-20T23:59:59.000Z",
                  "orderNumber": "04-HBCPTBGQJ6",
                  "shippingExtensionRequested": false,
                  "productVariant": {
                    "id": "variant-pending",
                    "traits": {"size": "3.5"},
                    "sizeChart": {"displayOptions": [{"size": "US M 3.5"}, {"size": "EU 35.5"}]},
                    "product": {
                      "title": "Nike Kobe 6 Protro",
                      "styleId": "FV4921-600"
                    }
                  }
                }
                """);

        TaskItemDO item = StockXOrderItemConverter.convertPending(90L, ask);

        assertThat(item.getTaskId()).isEqualTo(90L);
        assertThat(item.getListingId()).isEqualTo("14958362-pending-ask");
        assertThat(item.getProductId()).isEqualTo("variant-pending");
        assertThat(item.getTitle()).isEqualTo("Nike Kobe 6 Protro");
        assertThat(item.getStyleId()).isEqualTo("FV4921-600");
        assertThat(item.getSize()).isEqualTo("3.5");
        assertThat(item.getEuSize()).isEqualTo("35.5");
        assertThat(item.getOrderNumber()).isEqualTo("04-HBCPTBGQJ6");
        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("648"));
        assertThat(item.getCurrencyCode()).isEqualTo("USD");
        assertThat(item.getSoldOn()).isNotNull();
        assertThat(item.getOperateTime()).isAfter(item.getSoldOn());
        assertThat(item.getOrderStatus()).isEqualTo("待处理");
        assertThat(item.getOperateResult()).isEqualTo("未延期");
    }

    @Test
    void convertsStockXOrderFieldsIntoTaskItemData() {
        JSONObject order = JSON.parseObject("""
                {
                  "id": "0e01e186-aaaa-bbbb-cccc-1234567890ab",
                  "amount": 198,
                  "currency": "USD",
                  "status": "COMPLETED",
                  "soldOn": "2026-07-06T03:04:05.000Z",
                  "associatedOrders": {
                    "standardizedSellOrder": {
                      "orderNumber": "04-UVW7RFTNQ"
                    }
                  },
                  "productVariant": {
                    "id": "variant-1",
                    "traits": {"size": "9.5"},
                    "sizeChart": {"displayOptions": [{"size": "US M 9.5"}, {"size": "EU 43"}]},
                    "product": {
                      "title": "Nike Air Force 1 Low",
                      "styleId": "IO4489-601"
                    }
                  }
                }
                """);

        TaskItemDO item = StockXOrderItemConverter.convert(
                88L, order, StockXOrderCategory.COMPLETED);

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
        assertThat(item.getSoldOn()).isNotNull();
        assertThat(item.getOperateResult()).isEqualTo("销售完成");
    }

    @Test
    void convertsPendingPayoutWithoutRequestingOrderDetails() {
        JSONObject order = JSON.parseObject("""
                {
                  "id": "listing-pending",
                  "amount": 222,
                  "currency": "USD",
                  "status": "MATCHED",
                  "soldOn": "2026-07-07T03:04:05.000Z",
                  "associatedOrders": {"standardizedSellOrder": {"orderNumber": "04-PENDING"}},
                  "productVariant": {
                    "id": "variant-2",
                    "traits": {"size": "8.5"},
                    "sizeChart": {"displayOptions": [{"size": "US M 8.5"}, {"size": "EU 42"}]},
                    "product": {"title": "Jordan 11 Retro", "styleId": "CT8012-047"}
                  }
                }
                """);

        TaskItemDO item = StockXOrderItemConverter.convert(
                89L, order, StockXOrderCategory.PENDING_PAYOUT);

        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("222"));
        assertThat(item.getOrderStatus()).isEqualTo("待付款");
        assertThat(item.getEuSize()).isEqualTo("42");
    }
}
