package cn.ken.shoes.scheduler;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
import cn.ken.shoes.util.LockHelper;
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
    private PoisonService poisonService;

    @Scheduled(cron = "0 0 0 * * *")
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_ITEMS, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public void refreshKcItems() {
        LockHelper.lockKcItem();
        kickScrewService.refreshItems(true);
        LockHelper.unlockKcItem();
        LockHelper.setKcItemStatus(true);
    }

    @Scheduled(cron = "15 0 0 * * *")
    @Task(platform = TaskDO.PlatformEnum.POISON, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public void refreshPoisonPrice() {
        LockHelper.setKcItemStatus(false);
        poisonService.refreshPrice(true);
    }

//    @Scheduled(fixedDelay = 100 * 60 * 1000, initialDelay = 80 * 60 * 1000)
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public void refreshKcPrice() {
        LockHelper.lockKcItem();
        kickScrewService.refreshPrices();
        int changeCnt = kickScrewService.compareWithPoisonAndChangePrice();
        LockHelper.unlockKcItem();
    }

}
