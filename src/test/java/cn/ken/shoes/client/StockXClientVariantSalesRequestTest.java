package cn.ken.shoes.client;

import com.alibaba.fastjson.JSONObject;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXClientVariantSalesRequestTest {
    @Test
    void buildsTheCurrentStockXProVariantSalesContract() {
        JSONObject request = StockXClient.buildVariantSalesRequest("variant-1", "US", null);
        assertThat(request.getString("operationName")).isEqualTo("VariantSales");
        assertThat(request.getJSONObject("variables"))
                .containsEntry("id", "variant-1")
                .containsEntry("currencyCode", "USD")
                .containsEntry("market", "US")
                .containsEntry("viewerContext", "BUYER");
        assertThat(request.getString("query"))
                .contains("sales(market:$market,after:$after,viewerContext:$viewerContext)")
                .contains("amount createdAt orderType")
                .contains("endCursor");
    }

    @Test
    void rejectsGraphQlErrorsInsteadOfTreatingThemAsNoSales() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                return JSONObject.parseObject("{\"errors\":[{\"message\":\"sales unavailable\"}]}");
            }
        };

        assertThatThrownBy(() -> client.queryVariantSales("variant-1", new StockXAccount()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sales unavailable");
    }

    @Test
    void rejectsMalformedResponsesInsteadOfTreatingThemAsNoSales() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryReadPro(String body, String country, StockXAccount preferredAccount) {
                return JSONObject.parseObject("{\"data\":{\"variant\":{}}}");
            }
        };

        assertThatThrownBy(() -> client.queryVariantSales("variant-1", new StockXAccount()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少sales字段");
    }
}
