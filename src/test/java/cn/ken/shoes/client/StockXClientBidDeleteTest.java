package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXBidDeleteItem;
import cn.ken.shoes.model.stockx.StockXBidDeleteResult;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StockXClientBidDeleteTest {

    @Test
    void buildsObservedAliasedDeleteBidMutationUsingChainIds() {
        JSONObject request = StockXClient.buildDeleteBidsRequest(List.of(
                new StockXBidDeleteItem("chain-1", "USD"),
                new StockXBidDeleteItem("chain-2", "USD")));

        assertThat(request.getString("operationName")).isEqualTo("DeleteBidBatch");
        assertThat(request.getString("query"))
                .contains("mutation DeleteBidBatch")
                .contains("d0: deleteBid(input: {chainId: \"chain-1\", currencyCode: USD})")
                .contains("d1: deleteBid(input: {chainId: \"chain-2\", currencyCode: USD})")
                .doesNotContain("bidId");
    }

    @Test
    void rejectsEmptyOversizedOrUnsafeDeleteBatches() {
        assertThat(catchThrowable(() -> StockXClient.buildDeleteBidsRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到10");
        List<StockXBidDeleteItem> oversized = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> new StockXBidDeleteItem("chain-" + i, "USD"))
                .toList();
        assertThat(catchThrowable(() -> StockXClient.buildDeleteBidsRequest(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到10");
        assertThat(catchThrowable(() -> StockXClient.buildDeleteBidsRequest(List.of(
                new StockXBidDeleteItem("bad\"id", "USD")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId");
    }

    @Test
    void mapsAliasedDeleteResponsesAndDistinguishesFailures() {
        List<StockXBidDeleteItem> input = List.of(
                new StockXBidDeleteItem("chain-1", "USD"),
                new StockXBidDeleteItem("chain-2", "USD"));
        JSONObject response = new JSONObject(true).fluentPut("data", new JSONObject(true)
                .fluentPut("d0", new JSONObject(true)
                        .fluentPut("status", "Bid chain-1 deleted successfully"))
                .fluentPut("d1", new JSONObject(true).fluentPut("status", "FAILED")));

        List<StockXBidDeleteResult> results = StockXClient.parseDeleteBidsResponse(response, input);

        assertThat(results).extracting(StockXBidDeleteResult::chainId)
                .containsExactly("chain-1", "chain-2");
        assertThat(results).extracting(StockXBidDeleteResult::success)
                .containsExactly(true, false);
        assertThat(results.get(1).status()).isEqualTo("FAILED");
    }

    @Test
    void rejectsPartialDeleteResponsesInsteadOfSilentlyDroppingItems() {
        List<StockXBidDeleteItem> input = List.of(
                new StockXBidDeleteItem("chain-1", "USD"),
                new StockXBidDeleteItem("chain-2", "USD"));
        JSONObject partial = new JSONObject(true).fluentPut("data", new JSONObject(true)
                .fluentPut("d0", new JSONObject(true)
                        .fluentPut("status", "Bid chain-1 deleted successfully")));

        assertThat(catchThrowable(() -> StockXClient.parseDeleteBidsResponse(partial, input)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("d1");
    }
}
