package cn.ken.shoes.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class StockXShippingExtensionSchedulerTest {

    @Test
    void runsEveryTwelveHoursByDefault() throws NoSuchMethodException {
        Scheduled scheduled = StockXShippingExtensionScheduler.class
                .getMethod("autoExtendPendingOrders")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${stockx.shipping-extension.initial-delay-ms:120000}");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${stockx.shipping-extension.interval-ms:43200000}");
    }
}
