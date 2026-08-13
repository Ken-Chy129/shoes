package cn.ken.shoes.manager;

import cn.ken.shoes.exception.StockXNoResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class StockXPriceRateStateManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void batchLimitSwitchesToSingleAndSchedulesARealProbe() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        StockXPriceRateStateManager manager = manager(clock);

        manager.onBatchLimit("account-a", "BatchUsageLimit");

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.mode()).isEqualTo(StockXPriceRateStateManager.Mode.SINGLE_FALLBACK);
        assertThat(snapshot.nextBatchProbeAt()).isEqualTo(clock.millis() + 310_000L);
        assertThat(snapshot.batchRateLimitCount()).isEqualTo(1L);
        assertThat(snapshot.bulkBatchRateLimitCount()).isEqualTo(1L);
        assertThat(snapshot.singleBatchRateLimitCount()).isZero();
        assertThat(manager.shouldProbeBatch("account-a")).isFalse();

        clock.advanceMillis(310_000L);
        assertThat(manager.shouldProbeBatch("account-a")).isTrue();
    }

    @Test
    void successfulProbeRecoversWithProgressiveBatchSizes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        StockXPriceRateStateManager manager = manager(clock);
        manager.onBatchLimit("account-a", "BatchUsageLimit");
        clock.advanceMillis(310_000L);

        manager.onBatchProbeSuccess("account-a");
        assertThat(manager.currentBulkBatchSize("account-a")).isEqualTo(20);

        manager.onRecoveryBatchSuccess("account-a");
        assertThat(manager.currentBulkBatchSize("account-a")).isEqualTo(50);

        manager.onRecoveryBatchSuccess("account-a");
        assertThat(manager.currentBulkBatchSize("account-a")).isEqualTo(100);

        manager.onRecoveryBatchSuccess("account-a");
        assertThat(manager.snapshot("account-a").mode())
                .isEqualTo(StockXPriceRateStateManager.Mode.BULK_ACTIVE);
    }

    @Test
    void globalLimitSchedulesThreeHourProbeWithoutStoppingTheTask() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        StockXPriceRateStateManager manager = manager(clock);

        manager.onGlobalLimit("account-a", "HTTP429");
        manager.onGlobalLimit("account-a", "HTTP429");

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.mode()).isEqualTo(StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN);
        assertThat(snapshot.nextGlobalProbeAt()).isEqualTo(clock.millis() + 3 * 60 * 60 * 1000L);
        assertThat(manager.globalCooldownRemainingMs("account-a")).isEqualTo(3 * 60 * 60 * 1000L);
        assertThat(snapshot.globalCooldownCount()).isEqualTo(1L);
    }

    @Test
    void blockingFailureUsesASeparateShorterCooldownAndCounter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        StockXPriceRateStateManager manager = manager(clock);

        manager.onBlocked("account-a", "HTTP_403");

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.mode()).isEqualTo(StockXPriceRateStateManager.Mode.BLOCKED_COOLDOWN);
        assertThat(snapshot.nextGlobalProbeAt()).isEqualTo(clock.millis() + 15 * 60 * 1000L);
        assertThat(snapshot.globalCooldownCount()).isZero();
        assertThat(snapshot.blockedCooldownCount()).isEqualTo(1L);
    }

    @Test
    void countersAreObservationalAndReportedPerAccount() {
        StockXPriceRateStateManager manager = manager(new MutableClock(Instant.parse("2026-07-17T08:00:00Z")));

        manager.recordBulkAttempt("account-a", 80, false);
        manager.recordSingleAttempt("account-a");
        manager.recordNoResponse("account-a");
        manager.recordConfirmed("account-a", 7);

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.bulkRequestCount()).isEqualTo(1L);
        assertThat(snapshot.bulkItemCount()).isEqualTo(80L);
        assertThat(snapshot.singleRequestCount()).isEqualTo(1L);
        assertThat(snapshot.noResponseCount()).isEqualTo(1L);
        assertThat(snapshot.confirmedPriceUpdateCount()).isEqualTo(7L);
    }

    @Test
    void diagnosticsSeparateBulkSingleBlockingAndNetworkFailures() {
        StockXPriceRateStateManager manager = manager(new MutableClock(Instant.parse("2026-07-17T08:00:00Z")));

        manager.onBatchLimit("account-a", "BatchUsageLimit");
        manager.recordBatchRateLimit("account-a", "BatchUsageLimit");
        manager.onBulkGeneralLimit("account-a", "HTTP429");
        manager.recordGeneralRateLimit("account-a", "HTTP429");
        manager.recordBulkFailure("account-a", StockXNoResponseException.FailureType.NETWORK_NO_RESPONSE);
        manager.recordSingleFailure("account-a", StockXNoResponseException.FailureType.HTTP_403);
        manager.recordSingleFailure("account-a", StockXNoResponseException.FailureType.BLOCK_SCRIPT);

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.batchRateLimitCount()).isEqualTo(2L);
        assertThat(snapshot.bulkBatchRateLimitCount()).isEqualTo(1L);
        assertThat(snapshot.singleBatchRateLimitCount()).isEqualTo(1L);
        assertThat(snapshot.generalRateLimitCount()).isEqualTo(2L);
        assertThat(snapshot.bulkGeneralRateLimitCount()).isEqualTo(1L);
        assertThat(snapshot.singleGeneralRateLimitCount()).isEqualTo(1L);
        assertThat(snapshot.noResponseCount()).isEqualTo(3L);
        assertThat(snapshot.networkNoResponseCount()).isEqualTo(1L);
        assertThat(snapshot.http403Count()).isEqualTo(1L);
        assertThat(snapshot.blockScriptCount()).isEqualTo(1L);
        assertThat(snapshot.bulkNetworkNoResponseCount()).isEqualTo(1L);
        assertThat(snapshot.singleNetworkNoResponseCount()).isZero();
        assertThat(snapshot.bulkHttp403Count()).isZero();
        assertThat(snapshot.singleHttp403Count()).isEqualTo(1L);
        assertThat(snapshot.bulkBlockScriptCount()).isZero();
        assertThat(snapshot.singleBlockScriptCount()).isEqualTo(1L);
        assertThat(snapshot.unclassifiedNoResponseCount()).isZero();
    }

    @Test
    void cooldownStateSurvivesAServiceRestart() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        Path stateFile = tempDir.resolve("rate-state.json");
        StockXPriceRateStateManager first = new StockXPriceRateStateManager(clock, stateFile);
        first.onGlobalLimit("account-a", "HTTP429");

        StockXPriceRateStateManager reloaded = new StockXPriceRateStateManager(clock, stateFile);

        assertThat(reloaded.snapshot("account-a").mode())
                .isEqualTo(StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN);
        assertThat(reloaded.globalCooldownRemainingMs("account-a"))
                .isEqualTo(3 * 60 * 60 * 1000L);
    }

    @Test
    void legacyAggregateCountersRemainVisibleAsUnclassified() throws Exception {
        Path stateFile = tempDir.resolve("legacy-rate-state.json");
        Files.writeString(stateFile, """
                {"account-a":{
                  "mode":"BULK_ACTIVE",
                  "batchRateLimitCount":9,
                  "generalRateLimitCount":3,
                  "noResponseCount":4
                }}
                """, StandardCharsets.UTF_8);

        StockXPriceRateStateManager manager = new StockXPriceRateStateManager(
                new MutableClock(Instant.parse("2026-07-17T08:00:00Z")), stateFile);

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.unclassifiedBatchRateLimitCount()).isEqualTo(9L);
        assertThat(snapshot.unclassifiedGeneralRateLimitCount()).isEqualTo(3L);
        assertThat(snapshot.unclassifiedNoResponseCount()).isEqualTo(4L);
    }

    @Test
    void onlyOneWorkerCanOwnAnAccountGlobalProbe() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        StockXPriceRateStateManager manager = manager(clock);
        manager.onGlobalLimit("account-a", "HTTP429");
        clock.advanceMillis(3 * 60 * 60 * 1000L);

        assertThat(manager.tryAcquireGlobalProbe("account-a")).isTrue();
        assertThat(manager.tryAcquireGlobalProbe("account-a")).isFalse();

        manager.releaseGlobalProbe("account-a");
        assertThat(manager.tryAcquireGlobalProbe("account-a")).isTrue();
    }

    private StockXPriceRateStateManager manager(Clock clock) {
        return new StockXPriceRateStateManager(clock, tempDir.resolve("rate-state.json"));
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

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
