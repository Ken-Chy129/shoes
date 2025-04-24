package cn.ken.shoes.scheduler;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.common.CustomPriceTypeEnum;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.SqlHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


@Slf4j
@Component
public class PriceScheduler {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private PriceManager priceManager;

    @Resource
    private CustomModelMapper customModelMapper;

    @Scheduled(initialDelay = 60 * 60 * 1000, fixedDelay = 30 * 60 * 1000)
    public void dumpPoisonPrice() {
        priceManager.dumpPrice();
    }

    @Scheduled(fixedDelay = 70 * 60 * 1000, initialDelay = 40 * 60 * 1000)
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.CHANGE_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public int refreshKcPrice() {
//        LockHelper.lockKcItem();
//        int changeCnt = kickScrewService.refreshPriceV2();
//        LockHelper.unlockKcItem();
//        return changeCnt;
        return 0;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    public void refreshNoPriceModelNo() {
        List<String> noPriceModelDOS = customModelMapper.selectByType(CustomPriceTypeEnum.NO_PRICE.getCode()).stream().map(CustomModelDO::getModelNo).toList();
        List<CustomModelDO> needRefresh = new ArrayList<>();
        Set<String> noPriceModelSet = ShoesContext.getNoPriceModelSet();
        for (String modelNo : noPriceModelSet) {
            if (noPriceModelDOS.contains(modelNo)) {
                continue;
            }
            CustomModelDO customModelDO = new CustomModelDO();
            customModelDO.setModelNo(modelNo);
            customModelDO.setType(CustomPriceTypeEnum.NO_PRICE.getCode());
        }
        SqlHelper.batch(needRefresh, model -> customModelMapper.insertIgnore(model));
    }

}
