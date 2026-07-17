package cn.ken.shoes.manager;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.exception.StockXNoResponseException;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.StockXRateLimitType;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXPriceUpdateCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void realBatchLimitFallsBackToSinglesWithoutStopping() {
        MutableClock clock = new MutableClock();
        FakeClient client = new FakeClient();
        client.bulkFailures.add(limit(StockXRateLimitType.BATCH, "BatchUsageLimit"));
        StockXPriceRateStateManager state = state(clock);
        StockXPriceUpdateCoordinator coordinator = coordinator(client, state, clock);

        StockXPriceUpdateCoordinator.Submission submission = coordinator.submit(items(2), account(),
                () -> false, null, null, null);

        assertThat(submission.submittedItems()).hasSize(2);
        assertThat(client.bulkCalls).isEqualTo(1);
        assertThat(client.singleIds).containsExactly("listing-1", "listing-2");
        assertThat(state.snapshot("account-a").mode())
                .isEqualTo(StockXPriceRateStateManager.Mode.SINGLE_FALLBACK);
    }

    @Test
    void bulkNoResponseUsesSinglesButDoesNotPretendThatQuotaWasLimited() {
        MutableClock clock = new MutableClock();
        FakeClient client = new FakeClient();
        client.bulkFailures.add(new StockXNoResponseException("no response"));
        StockXPriceRateStateManager state = state(clock);

        coordinator(client, state, clock).submit(items(1), account(), () -> false, null, null, null);

        assertThat(client.singleIds).containsExactly("listing-1");
        assertThat(state.snapshot("account-a").mode())
                .isEqualTo(StockXPriceRateStateManager.Mode.BULK_ACTIVE);
        assertThat(state.snapshot("account-a").noResponseCount()).isEqualTo(1);
    }

    @Test
    void bothChannelsStillLimitedEnterThreeHourCooldownAndRemainCancellable() {
        MutableClock clock = new MutableClock();
        FakeClient client = new FakeClient();
        client.bulkFailures.add(limit(StockXRateLimitType.GENERAL, "HTTP429"));
        client.alwaysLimitSingle = true;
        StockXPriceRateStateManager state = state(clock);
        AtomicBoolean cancelled = new AtomicBoolean();
        StockXPriceUpdateCoordinator coordinator = new StockXPriceUpdateCoordinator(client, state, millis -> {
            clock.advanceMillis(millis);
            if (state.mode("account-a") == StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN) {
                cancelled.set(true);
            }
        });

        assertThatThrownBy(() -> coordinator.submit(items(1), account(), cancelled::get, null, null, null))
                .isInstanceOf(TaskCancelledException.class);

        assertThat(state.snapshot("account-a").mode())
                .isEqualTo(StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN);
        assertThat(state.snapshot("account-a").generalRateLimitCount()).isEqualTo(5);
        assertThat(client.singleIds).hasSize(4);
    }

    @Test
    void dueBatchProbeUsesARealPendingItemAndStartsProgressiveRecovery() {
        MutableClock clock = new MutableClock();
        FakeClient client = new FakeClient();
        StockXPriceRateStateManager state = state(clock);
        state.onBatchLimit("account-a", "BatchUsageLimit");
        clock.advanceMillis(310_000L);

        StockXPriceUpdateCoordinator.Submission result = coordinator(client, state, clock)
                .submit(items(1), account(), () -> false, null, null, null);

        assertThat(result.submittedItems()).hasSize(1);
        assertThat(client.bulkCalls).isEqualTo(1);
        assertThat(client.singleIds).isEmpty();
        assertThat(state.snapshot("account-a").mode())
                .isEqualTo(StockXPriceRateStateManager.Mode.BULK_RECOVERING);
        assertThat(state.currentBulkBatchSize("account-a")).isEqualTo(20);
    }

    @Test
    void globalProbeRechecksAnExtendedAccountCooldownBeforeCallingStockx() {
        MutableClock clock = new MutableClock();
        StockXPriceRateStateManager state = state(clock);
        state.onGlobalLimit("account-a", "HTTP429");
        long initialDeadline = state.snapshot("account-a").nextGlobalProbeAt();
        AtomicLong singleCallAt = new AtomicLong();
        FakeClient client = new FakeClient() {
            @Override
            public void updateSellerListingGraphql(Map<String, String> item, StockXAccount account) {
                singleCallAt.set(clock.millis());
                super.updateSellerListingGraphql(item, account);
            }
        };
        AtomicBoolean extended = new AtomicBoolean();
        StockXPriceUpdateCoordinator coordinator = new StockXPriceUpdateCoordinator(client, state, millis -> {
            clock.advanceMillis(millis);
            if (extended.compareAndSet(false, true)) {
                state.onGlobalLimit("account-a", "HTTP429");
            }
        });

        coordinator.submit(items(1), account(), () -> false, null, null, null);

        assertThat(singleCallAt.get()).isEqualTo(initialDeadline + 5_000L);
    }

    private StockXPriceUpdateCoordinator coordinator(FakeClient client,
                                                      StockXPriceRateStateManager state,
                                                      MutableClock clock) {
        return new StockXPriceUpdateCoordinator(client, state, clock::advanceMillis);
    }

    private StockXPriceRateStateManager state(Clock clock) {
        return new StockXPriceRateStateManager(clock, tempDir.resolve("rate-state.json"));
    }

    private static StockXAccount account() {
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        return account;
    }

    private static List<Map<String, String>> items(int count) {
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(Map.of("listingId", "listing-" + i, "amount", String.valueOf(300 + i)));
        }
        return items;
    }

    private static StockXRateLimitException limit(StockXRateLimitType type, String signal) {
        return new StockXRateLimitException("account-a", 0, "limited", type, signal);
    }

    private static class FakeClient extends StockXClient {
        int bulkCalls;
        boolean alwaysLimitSingle;
        final List<RuntimeException> bulkFailures = new ArrayList<>();
        final List<String> singleIds = new ArrayList<>();

        @Override
        public String batchUpdateListingsGraphql(List<Map<String, String>> items, StockXAccount account) {
            bulkCalls++;
            if (!bulkFailures.isEmpty()) {
                throw bulkFailures.remove(0);
            }
            return "batch-" + bulkCalls;
        }

        @Override
        public void updateSellerListingGraphql(Map<String, String> item, StockXAccount account) {
            singleIds.add(item.get("listingId"));
            if (alwaysLimitSingle) {
                throw limit(StockXRateLimitType.GENERAL, "HTTP429");
            }
        }
    }

    private static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-17T08:00:00Z");

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
