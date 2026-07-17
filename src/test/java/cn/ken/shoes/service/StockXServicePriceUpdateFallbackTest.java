package cn.ken.shoes.service;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.exception.StockXNoResponseException;
import cn.ken.shoes.manager.StockXPriceRateStateManager;
import cn.ken.shoes.manager.StockXPriceUpdateCoordinator;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXServicePriceUpdateFallbackTest {

    @Test
    void fallsBackToIndividualUpdatesWhenBulkRequestHasNoResponse() throws Exception {
        FakeStockXClient client = new FakeStockXClient();
        client.bulkFailure = new StockXNoResponseException("提交失败:无响应(网络异常或被拦截)");
        StockXService service = serviceWith(client);
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        List<Map<String, String>> items = List.of(
                Map.of("listingId", "listing-1", "amount", "300", "currencyCode", "USD"),
                Map.of("listingId", "listing-2", "amount", "301", "currencyCode", "USD"));

        StockXService.PriceDownSubmission submission = service.submitPriceDownBatch(
                items, account, "STANDARD", Map.of());

        assertThat(client.bulkCalls).isEqualTo(1);
        assertThat(client.individualListingIds).containsExactly("listing-1", "listing-2");
        assertThat(submission.reference()).isEqualTo("StockX-price-update");
        assertThat(submission.submittedItems()).containsExactlyElementsOf(items);
    }

    @Test
    void doesNotFallbackForAnExplicitBulkBusinessFailure() throws Exception {
        FakeStockXClient client = new FakeStockXClient();
        client.bulkFailure = new RuntimeException("提交失败:价格格式无效");
        StockXService service = serviceWith(client);
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        List<Map<String, String>> items = List.of(
                Map.of("listingId", "listing-1", "amount", "300", "currencyCode", "USD"));

        assertThatThrownBy(() -> service.submitPriceDownBatch(items, account, "STANDARD", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("提交失败:价格格式无效");
        assertThat(client.individualListingIds).isEmpty();
    }

    private static StockXService serviceWith(StockXClient client) throws Exception {
        StockXService service = new StockXService();
        Field field = StockXService.class.getDeclaredField("stockXClient");
        field.setAccessible(true);
        field.set(service, client);
        StockXPriceRateStateManager state = new StockXPriceRateStateManager(
                Clock.systemUTC(), Files.createTempDirectory("stockx-rate-test").resolve("state.json"));
        Field coordinator = StockXService.class.getDeclaredField("priceUpdateCoordinator");
        coordinator.setAccessible(true);
        coordinator.set(service, new StockXPriceUpdateCoordinator(client, state));
        return service;
    }

    private static class FakeStockXClient extends StockXClient {
        private RuntimeException bulkFailure;
        private int bulkCalls;
        private final List<String> individualListingIds = new ArrayList<>();

        @Override
        public String batchUpdateListingsGraphql(List<Map<String, String>> items, StockXAccount account) {
            bulkCalls++;
            if (bulkFailure != null) {
                throw bulkFailure;
            }
            return "batch-1";
        }

        @Override
        public void updateSellerListingGraphql(Map<String, String> item, StockXAccount account) {
            individualListingIds.add(item.get("listingId"));
        }
    }
}
