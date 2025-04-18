package cn.ken.shoes.scheduler;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.LockHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class PriceScheduler {

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private KickScrewService kickScrewService;

    @Scheduled(cron = "0 0 0 * * *")
    public void clearOldPoisonPrice() {
        poisonPriceMapper.delete(null);
    }

    @Scheduled(fixedDelay = 70 * 60 * 1000, initialDelay = 40 * 60 * 1000)
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.CHANGE_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public int refreshKcPrice() {
        LockHelper.lockKcItem();
        int changeCnt = kickScrewService.refreshPriceV2();
        LockHelper.unlockKcItem();
        return changeCnt;
    }

}
