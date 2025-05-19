package cn.ken.shoes.scheduler;

import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.service.PriceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class PriceScheduler {

    @Resource
    private PriceManager priceManager;

    @Resource
    private PriceService priceService;

    @Scheduled(initialDelay = 60 * 60 * 1000, fixedDelay = 30 * 60 * 1000)
    public void dumpPoisonPrice() {
        priceManager.dumpPrice();
    }


    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    public void refreshNoPriceModelNo() {
        priceService.refreshNoPriceModel();
    }

}
