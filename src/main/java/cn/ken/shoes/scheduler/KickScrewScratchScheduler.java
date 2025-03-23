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

    @Scheduled(cron = "0 9 0 * * *")
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_ITEMS, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public void refreshKcItems() {
        LockHelper.lockKcItem();
        kickScrewService.refreshItems(true);
        LockHelper.unlockKcItem();
        LockHelper.setKcItemStatus(true);
    }

    @Scheduled(fixedDelay = 70 * 60 * 1000, initialDelay = 40 * 60 * 1000)
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.CHANGE_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public int refreshKcPrice() {
        LockHelper.lockKcItem();
        int changeCnt = kickScrewService.refreshPriceV2();
//        kickScrewService.refreshPrices();
//        int changeCnt = kickScrewService.compareWithPoisonAndChangePrice();
        LockHelper.unlockKcItem();
        return changeCnt;
    }

}
