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

        kickScrewService.scratchAndSaveBrand();
        kickScrewItemService.refreshAllItems();
    }

}
