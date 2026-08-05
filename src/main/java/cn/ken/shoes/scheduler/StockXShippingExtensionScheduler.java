package cn.ken.shoes.scheduler;

import cn.ken.shoes.service.StockXReplenishmentService;
import cn.ken.shoes.service.StockXShippingExtensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StockXShippingExtensionScheduler {

    private final StockXShippingExtensionService shippingExtensionService;
    private final StockXReplenishmentService replenishmentService;

    public StockXShippingExtensionScheduler(StockXShippingExtensionService shippingExtensionService,
                                            StockXReplenishmentService replenishmentService) {
        this.shippingExtensionService = shippingExtensionService;
        this.replenishmentService = replenishmentService;
    }

    @Scheduled(
            initialDelayString = "${stockx.shipping-extension.interval-ms:43200000}",
            fixedDelayString = "${stockx.shipping-extension.interval-ms:43200000}")
    public void autoExtendPendingOrders() {
        try {
            shippingExtensionService.extendAllEnabledAccounts("scheduled");
        } catch (Exception e) {
            log.error("StockX自动延期定时任务异常", e);
        }
        try {
            replenishmentService.replenishAllEnabledAccountsLastHours(12, "scheduled");
        } catch (Exception e) {
            log.error("StockX自动补单定时任务异常", e);
        }
    }
}
