package cn.ken.shoes.client;

import cn.ken.shoes.common.StockXOrderCategory;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientOrderRequestTest {

    @Test
    void buildsCapturedSellerListingsRequestForPendingPayout() {
        JSONObject request = StockXClient.buildOrderListingsRequest(
                StockXOrderCategory.PENDING_PAYOUT, 3, "HK");

        assertThat(request.getString("operationName")).isEqualTo("SellerListings");
        assertThat(request.getJSONObject("variables").getInteger("pageSize")).isEqualTo(50);
        assertThat(request.getJSONObject("variables").getInteger("pageNumber")).isEqualTo(3);
        assertThat(request.getJSONObject("variables").getJSONObject("filters")
                .getJSONObject("listingStatus").getJSONArray("in").toJavaList(String.class))
                .containsExactly("MATCHED");
        assertThat(request.getJSONObject("variables").getJSONObject("filters")
                .getJSONObject("orderStatus").getJSONArray("in").toJavaList(String.class))
                .containsExactly("PAYOUTPENDING", "PAYOUTCOMPLETED", "PAYOUTFAILED");
        assertThat(request.getJSONObject("extensions").getJSONObject("persistedQuery")
                .getString("sha256Hash"))
                .isEqualTo("0be46d884e6e6945514543ade66ea6f8c7d081bdd799623ac1d7b4e16348b733");
    }

    @Test
    void buildsCapturedPayoutRequestByListingId() {
        JSONObject request = StockXClient.buildOrderPayoutRequest("listing-123");

        assertThat(request.getString("operationName")).isEqualTo("SellerListingStandardizedSellOrder");
        assertThat(request.getJSONObject("variables").getString("id")).isEqualTo("listing-123");
        assertThat(request.getJSONObject("extensions").getJSONObject("persistedQuery")
                .getString("sha256Hash"))
                .isEqualTo("f94ee23520a6ce8aa553f97ec790d67a81b6f0ab652a6dd20178ca21c3f4695e");
    }
}
