package cn.ken.shoes.scheduler;

import cn.ken.shoes.service.StockXShippingExtensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StockXShippingExtensionScheduler {

    private final StockXShippingExtensionService shippingExtensionService;

    public StockXShippingExtensionScheduler(StockXShippingExtensionService shippingExtensionService) {
        this.shippingExtensionService = shippingExtensionService;
    }

    @Scheduled(
            initialDelayString = "${stockx.shipping-extension.initial-delay-ms:120000}",
            fixedDelayString = "${stockx.shipping-extension.interval-ms:43200000}")
    public void autoExtendPendingOrders() {
        try {
            shippingExtensionService.extendAllEnabledAccounts("scheduled");
        } catch (Exception e) {
            log.error("StockX自动延期定时任务异常", e);
        }
    }
}
