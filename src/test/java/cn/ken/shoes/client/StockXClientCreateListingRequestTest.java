package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXListingCreateItem;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockXClientCreateListingRequestTest {

    @Test
    void buildsBatchListingRequestWithRequestedPriceAndQuantity() {
        JSONObject request = StockXClient.buildCreateBatchListingsRequest(
                List.of(new StockXListingCreateItem("variant-1", new BigDecimal("321"), 4)),
                "2027-07-17T07:33:10+00:00");

        assertThat(request.getString("operationName")).isEqualTo("CreateBatchListings");
        JSONArray items = request.getJSONObject("variables").getJSONArray("items");
        assertThat(items).hasSize(1);
        JSONObject item = items.getJSONObject(0);
        assertThat(item.getString("variantID")).isEqualTo("variant-1");
        assertThat(item.getString("amount")).isEqualTo("321");
        assertThat(item.getIntValue("quantity")).isEqualTo(4);
        assertThat(item.getString("inventoryType")).isEqualTo("STANDARD");
    }
}
