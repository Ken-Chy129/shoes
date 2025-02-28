package cn.ken.shoes.scheduler;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.service.KickScrewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class KickScrewScratchScheduler {

    @Resource
    private KickScrewService kickScrewService;

    @Scheduled(cron = "0 0 0 * * *")
    @Task
    public void updateItems() {
        log.info("update items start");
        kickScrewService.refreshItems();
        log.info("update items end");
    }

    @Scheduled(fixedDelay = 80 * 60 * 1000, initialDelay = 80 * 60 * 1000)
    public void updatePrice() {
        kickScrewService.refreshPrices();
    }

}
