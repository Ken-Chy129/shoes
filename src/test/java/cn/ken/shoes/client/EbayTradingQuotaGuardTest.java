package cn.ken.shoes.client;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EbayTradingQuotaGuardTest {

    @Test
    void blocksUntilNextPacificMidnightThenRecovers() {
        // 2026-11-15 07:25 UTC = 太平洋 11-14 23:25 (PST)，重置时间应为 11-15 08:00 UTC（北京时间 16:00）。
        MutableClock clock = new MutableClock(Instant.parse("2026-11-15T07:25:00Z"));
        EbayTradingQuotaGuard guard = new EbayTradingQuotaGuard(clock);

        EbayQuotaExceededException tripped = guard.tripped("GetOrders");

        assertThat(guard.getBlockedUntil()).isEqualTo(Instant.parse("2026-11-15T08:00:00Z"));
        assertThat(tripped.getMessage()).contains("：518 ").contains("11-15 16:00");
        assertThatThrownBy(() -> guard.checkAvailable("GetSellerList"))
                .isInstanceOf(EbayQuotaExceededException.class)
                .hasMessageContaining("eBay GetSellerList失败：518 ");

        clock.now.set(Instant.parse("2026-11-15T08:00:01Z"));
        assertThatCode(() -> guard.checkAvailable("GetSellerList")).doesNotThrowAnyException();
        assertThat(guard.getBlockedUntil()).isNull();
    }

    @Test
    void resetsStatsWhenPacificDayChanges() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-15T07:59:00Z"));
        EbayTradingQuotaGuard guard = new EbayTradingQuotaGuard(clock);
        guard.recordRequest("ReviseInventoryStatus");
        guard.recordRequest("ReviseInventoryStatus");
        assertThat(guard.totalRequests()).isEqualTo(2);

        clock.now.set(Instant.parse("2026-11-15T08:00:30Z"));

        assertThat(guard.totalRequests()).isZero();
        assertThat(guard.snapshot()).isEmpty();
        assertThat(guard.getStatsDay()).hasToString("2026-11-15");
    }

    @Test
    void clearBlockAllowsCallsAgain() {
        EbayTradingQuotaGuard guard = new EbayTradingQuotaGuard();
        guard.tripped("GetOrders");
        guard.clearBlock("test");
        assertThatCode(() -> guard.checkAvailable("GetOrders")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public java.time.ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(java.time.ZoneId other) {
                    return self.withZone(other);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
