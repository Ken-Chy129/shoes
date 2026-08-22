package cn.ken.shoes.client;

import cn.ken.shoes.model.stockx.StockXBidBatch;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StockXClientBidResponseTest {

    @Test
    void acceptsAQueuedCreateBidsBatch() {
        JSONObject response = new JSONObject(true).fluentPut("data", new JSONObject(true)
                .fluentPut("createBids", new JSONObject(true)
                        .fluentPut("id", "batch-1")
                        .fluentPut("status", "QUEUED")));

        StockXBidBatch batch = StockXClient.parseCreateBidsResponse(response);

        assertThat(batch.id()).isEqualTo("batch-1");
        assertThat(batch.status()).isEqualTo("QUEUED");
    }

    @Test
    void rejectsFailedOrMalformedCreateBidsResponses() {
        JSONObject failed = new JSONObject(true).fluentPut("data", new JSONObject(true)
                .fluentPut("createBids", new JSONObject(true)
                        .fluentPut("id", "batch-2")
                        .fluentPut("status", "CREATION_FAILED")));
        JSONObject malformed = new JSONObject(true).fluentPut("data", new JSONObject(true));

        assertThat(catchThrowable(() -> StockXClient.parseCreateBidsResponse(failed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATION_FAILED");
        assertThat(catchThrowable(() -> StockXClient.parseCreateBidsResponse(malformed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少批次");
    }

    @Test
    void parsesBulkUpdateBidsBatchAndRejectsFailedResponses() {
        JSONObject queued = new JSONObject(true).fluentPut("data", new JSONObject(true)
                .fluentPut("updateBids", new JSONObject(true)
                        .fluentPut("id", "update-batch-1")
                        .fluentPut("status", "QUEUED")));
        JSONObject failed = new JSONObject(true).fluentPut("data", new JSONObject(true)
                .fluentPut("updateBids", new JSONObject(true)
                        .fluentPut("id", "update-batch-2")
                        .fluentPut("status", "FAILED")));

        StockXBidBatch batch = StockXClient.parseUpdateBidsResponse(queued);

        assertThat(batch.id()).isEqualTo("update-batch-1");
        assertThat(batch.status()).isEqualTo("QUEUED");
        assertThat(catchThrowable(() -> StockXClient.parseUpdateBidsResponse(failed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }
}
