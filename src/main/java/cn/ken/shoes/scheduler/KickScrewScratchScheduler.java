package cn.ken.shoes.scheduler;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Slf4j
@Component
public class KickScrewScratchScheduler {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private PoisonService poisonService;

    @Scheduled(cron = "0 0 0 * * *")
    @Task
    public void updateItems() {
        log.info("update items start");
        kickScrewService.refreshItems();
        List<String> allModelNos = poisonService.getAllModelNos();
        poisonService.updatePoisonItems(allModelNos);
        poisonService.refreshPriceByModelNos(allModelNos);
        log.info("update items end");
    }

    @Scheduled(fixedDelay = 100 * 60 * 1000, initialDelay = 80 * 60 * 1000)
    @Task
    public void updatePrice() {
        kickScrewService.refreshPrices();
    }

}
