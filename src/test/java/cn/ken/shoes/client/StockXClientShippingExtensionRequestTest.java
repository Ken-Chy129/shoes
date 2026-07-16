package cn.ken.shoes.client;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientShippingExtensionRequestTest {

    @Test
    void buildsCapturedPendingAsksRequestWithCursorAndEligibilityFilters() {
        JSONObject request = StockXClient.buildPendingAsksRequest("cursor-2", "US");

        assertThat(request.getString("operationName")).isEqualTo("ViewerAsks");
        JSONObject variables = request.getJSONObject("variables");
        assertThat(variables.getString("after")).isEqualTo("cursor-2");
        assertThat(variables.getString("state")).isEqualTo("PENDING");
        assertThat(variables.getInteger("pageSize")).isEqualTo(30);
        assertThat(variables.getJSONObject("filters").getJSONObject("statesList")
                .getJSONArray("in").toJavaList(Integer.class))
                .containsExactly(410, 411, 415);
        assertThat(variables.getJSONObject("filters").getJSONObject("inventoryType")
                .getJSONArray("in").toJavaList(String.class))
                .containsExactly("STANDARD");
        assertThat(variables.getJSONObject("filters").getJSONObject("askType")
                .getJSONArray("in").toJavaList(String.class))
                .containsExactly("STANDARD", "AUCTION");
        assertThat(request.getJSONObject("extensions").getJSONObject("persistedQuery")
                .getString("sha256Hash"))
                .isEqualTo("960abd9d0f94d676dc187d3eca887f6de57d182c1fbbed124da1927ad1d3581c");
    }

    @Test
    void buildsCapturedExtendShipDateRequestUsingAskIdAsChainId() {
        JSONObject request = StockXClient.buildExtendShipDateRequest("04-ORDER", "14966898583202322174");

        assertThat(request.getString("operationName")).isEqualTo("ExtendShipDate");
        assertThat(request.getJSONObject("variables").getString("orderId")).isEqualTo("04-ORDER");
        assertThat(request.getJSONObject("variables").getString("chainId"))
                .isEqualTo("14966898583202322174");
        assertThat(request.getJSONObject("variables").getString("note"))
                .isEqualTo("Seller self-serve shipping extension");
        assertThat(request.getJSONObject("extensions").getJSONObject("persistedQuery")
                .getString("sha256Hash"))
                .isEqualTo("356331b5cf0da7f170d55185e49db5188ba5201cf97645ac3ef3f4de1ccd3149");
    }
}
