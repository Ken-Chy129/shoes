package cn.ken.shoes.scheduler;

import cn.ken.shoes.service.StockXReplenishmentService;
import cn.ken.shoes.service.StockXShippingExtensionService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StockXShippingExtensionSchedulerTest {

    @Test
    void waitsOneFullIntervalBeforeTheFirstAutomaticRun() throws NoSuchMethodException {
        Scheduled scheduled = StockXShippingExtensionScheduler.class
                .getMethod("autoExtendPendingOrders")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${stockx.shipping-extension.interval-ms:43200000}");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${stockx.shipping-extension.interval-ms:43200000}");
    }

    @Test
    void runsShippingExtensionAndPastTwelveHourReplenishmentTogether() {
        StockXShippingExtensionService extensionService = mock(StockXShippingExtensionService.class);
        StockXReplenishmentService replenishmentService = mock(StockXReplenishmentService.class);
        StockXShippingExtensionScheduler scheduler = new StockXShippingExtensionScheduler(
                extensionService, replenishmentService);

        scheduler.autoExtendPendingOrders();

        verify(extensionService).extendAllEnabledAccounts("scheduled");
        verify(replenishmentService).replenishAllEnabledAccountsLastHours(12, "scheduled");
    }
}
