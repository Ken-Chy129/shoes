package cn.ken.shoes.client;

import com.alibaba.fastjson.JSONObject;
import cn.ken.shoes.model.stockx.StockXAccount;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientSellingItemsRequestTest {

    @Test
    void preservesPurchaseOriginWhenFlatteningCustodialListings() {
        StockXClient client = new StockXClient() {
            @Override
            protected JSONObject queryPro(String body, Headers headers, String accountName) {
                return JSONObject.parseObject("""
                        {"data":{"viewer":{"sellerListings":{
                          "pageInfo":{"hasNextPage":false},"edges":[{"node":{
                            "id":"listing-1","amount":100,
                            "associatedOrders":{"flexIntakeOrder":{"sources":[
                              {"type":"BUY_INTO_FLEX","id":"03-BUY"}]}},
                            "productVariant":{"id":"variant-1","product":{"styleId":"HF3704-003"}}
                          }}]}}}}
                        """);
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("test");
        account.setAuthorization("test-only");
        JSONObject result = client.querySellingItemsByInventoryType("CUSTODIAL", 1, account);
        assertThat(result.getJSONArray("items").getJSONObject(0).getString("purchaseOrderNumber"))
                .isEqualTo("03-BUY");
    }

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
