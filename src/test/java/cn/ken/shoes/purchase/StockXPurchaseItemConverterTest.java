package cn.ken.shoes.purchase;

import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.task.StockXPurchaseItemConverter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StockXPurchaseItemConverterTest {

    @Test
    void convertsCurrentBidToTaskItem() {
        JSONObject bid = JSON.parseObject("""
                {
                  "id":"bid-1","state":100,"amount":175,"currencyCode":"USD",
                  "creationDate":"2026-08-22T01:02:03Z",
                  "productVariant":{
                    "id":"variant-1","traits":{"size":"10"},
                    "sizeChart":{"displayOptions":[{"size":"US M 10"},{"size":"EU 44"}]},
                    "product":{"id":"product-1","title":"Toro Bravo (2026)","styleId":"FQ8138-600"}
                  }
                }
                """);

        JSONObject market = JSON.parseObject("""
                {
                  "state":{"askServiceLevels":{
                    "standard":{"lowest":{"amount":400}},
                    "expressStandard":{"lowest":{"amount":443}}
                  }},
                  "priceLevels":{"edges":[
                    {"node":{"amount":308,"count":2}},
                    {"node":{"amount":307,"count":1}}
                  ]}
                }
                """);
        TaskItemDO item = StockXPurchaseItemConverter.convert(
                42L, bid, StockXPurchaseOperation.BIDS, market);

        assertThat(item.getTaskId()).isEqualTo(42L);
        assertThat(item.getListingId()).isEqualTo("bid-1");
        assertThat(item.getProductId()).isEqualTo("variant-1");
        assertThat(item.getTitle()).isEqualTo("Toro Bravo (2026)");
        assertThat(item.getStyleId()).isEqualTo("FQ8138-600");
        assertThat(item.getSize()).isEqualTo("10");
        assertThat(item.getEuSize()).isEqualTo("44");
        assertThat(item.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("175"));
        assertThat(item.getCurrencyCode()).isEqualTo("USD");
        assertThat(item.getOrderStatus()).isEqualTo("有效出价");
        assertThat(item.getOperateResult()).isEqualTo("有效出价");
        assertThat(item.getOperateTime()).isNotNull();
        assertThat(item.getLowestPrice()).isEqualByComparingTo("400");
        assertThat(item.getFlexLowestPrice()).isEqualByComparingTo("443");
        assertThat(item.getHighestBidPrice()).isEqualByComparingTo("308");
        assertThat(item.getHighestBidCount()).isEqualTo(2);
        assertThat(item.getSecondHighestBidPrice()).isEqualByComparingTo("307");
        assertThat(item.getSecondHighestBidCount()).isEqualTo(1);
    }

    @Test
    void convertsPurchaseOrderAndKeepsBuyerStatus() {
        JSONObject order = JSON.parseObject("""
                {
                  "chainId":"chain-1","amount":218,"currencyCode":"USD",
                  "purchaseDate":"2026-08-18T04:05:06Z","orderNumber":"01-FGEWDGWXVG",
                  "state":{"statusKey":"AUTHENTICATING","statusTitle":"验证中"},
                  "productVariant":{
                    "id":"variant-2","traits":{"size":"6Y"},
                    "sizeChart":{"displayOptions":[{"size":"US 6Y"},{"size":"EU 38.5"}]},
                    "product":{"id":"product-2","title":"Black University Blue (2026) (GS)","styleId":"440888-008"}
                  }
                }
                """);

        TaskItemDO item = StockXPurchaseItemConverter.convert(43L, order, StockXPurchaseOperation.ORDERS);

        assertThat(item.getListingId()).isEqualTo("chain-1");
        assertThat(item.getProductId()).isEqualTo("variant-2");
        assertThat(item.getOrderNumber()).isEqualTo("01-FGEWDGWXVG");
        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("218"));
        assertThat(item.getOrderStatus()).isEqualTo("验证中");
        assertThat(item.getOperateResult()).isEqualTo("验证中");
        assertThat(item.getSoldOn()).isNotNull();
        assertThat(item.getOperateTime()).isEqualTo(item.getSoldOn());
    }
}
