package cn.ken.shoes.scheduler;

import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class KickScrewScratchScheduler {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private ItemService kickScrewItemService;

    @Resource
    private PoisonService poisonService;

    @Scheduled(cron = "0 0 0 * * *")
    public void updateItems() {
        log.info("update items start");
        kickScrewService.scratchAndSaveBrand();
        kickScrewItemService.refreshAllItems();
        log.info("update items end");
    }

    @Scheduled(fixedDelay = 80 * 60 * 1000, initialDelay = 80 * 60 * 1000)
    public void updateKcPrices() {
        log.info("update kc prices start");
        kickScrewItemService.refreshAllPricesV2();
        log.info("update kc prices end");
    }

}
