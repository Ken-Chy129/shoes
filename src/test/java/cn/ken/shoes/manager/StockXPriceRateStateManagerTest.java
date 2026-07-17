package cn.ken.shoes.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        StockXPriceRateStateManager.Snapshot snapshot = manager.snapshot("account-a");
        assertThat(snapshot.mode()).isEqualTo(StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN);
        assertThat(snapshot.nextGlobalProbeAt()).isEqualTo(clock.millis() + 3 * 60 * 60 * 1000L);
        assertThat(manager.globalCooldownRemainingMs("account-a")).isEqualTo(3 * 60 * 60 * 1000L);
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
