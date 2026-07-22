package cn.ken.shoes.client;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientSellingItemsRequestTest {

    @Test
    void buildsSellerListingsSearchRequestByStyleId() {
        JSONObject request = StockXClient.buildSellingItemsSearchRequest(
                "STANDARD", 2, "HK", "IF4396-104");

        assertThat(request.getString("operationName")).isEqualTo("SellerListings");
        JSONObject variables = request.getJSONObject("variables");
        assertThat(variables.getString("query")).isEqualTo("IF4396-104");
        assertThat(variables.getInteger("pageSize")).isEqualTo(50);
        assertThat(variables.getInteger("pageNumber")).isEqualTo(2);
        assertThat(variables.getString("country")).isEqualTo("HK");
        assertThat(variables.getJSONObject("filters").getJSONObject("inventoryType")
                .getJSONArray("in").toJavaList(String.class)).containsExactly("STANDARD");
        assertThat(variables.getJSONObject("filters").getJSONObject("listingStatus")
                .getJSONArray("in").toJavaList(String.class)).containsExactly("ACTIVE");
        assertThat(variables.getJSONObject("filters").getJSONObject("listingType")
                .getJSONArray("in").toJavaList(String.class)).containsExactly("VERIFIED");
        assertThat(request.getJSONObject("extensions").getJSONObject("persistedQuery")
                .getString("sha256Hash"))
                .isEqualTo("0be46d884e6e6945514543ade66ea6f8c7d081bdd799623ac1d7b4e16348b733");
    }
}
