package cn.ken.shoes.scheduler;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.NoPriceModelMapper;
import cn.ken.shoes.model.entity.NoPriceModelDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.LockHelper;
import cn.ken.shoes.util.SqlHelper;
import cn.ken.shoes.util.TimeUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Component
public class PriceScheduler {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private NoPriceModelMapper noPriceModelMapper;

    @Resource
    private PriceManager priceManager;

    @Scheduled(initialDelay = 60 * 60 * 1000, fixedDelay = 30 * 60 * 1000)
    public void dumpPoisonPrice() {
        priceManager.dumpPrice();
    }

    @Scheduled(fixedDelay = 70 * 60 * 1000, initialDelay = 40 * 60 * 1000)
    @Task(platform = TaskDO.PlatformEnum.KC, taskType = TaskDO.TaskTypeEnum.CHANGE_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public int refreshKcPrice() {
        LockHelper.lockKcItem();
        int changeCnt = kickScrewService.refreshPriceV2();
        LockHelper.unlockKcItem();
        return changeCnt;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    public void refreshNoPriceModelNo() {
        Set<String> noPriceModelDOS = noPriceModelMapper.selectList(new QueryWrapper<>()).stream().map(NoPriceModelDO::getModelNo).collect(Collectors.toSet());
        List<String> needRefresh = new ArrayList<>();
        Set<String> noPriceModelNoSet = ShoesContext.getNoPriceModelNoSet();
        for (String modelNo : noPriceModelNoSet) {
            if (noPriceModelDOS.contains(modelNo)) {
                continue;
            }
            needRefresh.add(modelNo);
        }
        SqlHelper.batch(needRefresh, modelNo -> noPriceModelMapper.insertIgnore(modelNo));
    }

}
