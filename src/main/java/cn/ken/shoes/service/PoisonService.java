package cn.ken.shoes.service;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.LockHelper;
import cn.ken.shoes.util.SqlHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PoisonService {

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private PriceManager priceManager;

    @Resource
    private ShoesService shoesService;

    /**
     * 增量更新得物商品（现在查得物价格的接口不需要spuId，因此没必要再查得物商品信息）
     */
    @Deprecated
    public void updatePoisonItems(List<String> modelNumbers) {
        List<String> existItems = poisonItemMapper.selectExistModelNos(modelNumbers);
        List<String> toInsert = modelNumbers.stream().filter(modelNumber -> !existItems.contains(modelNumber)).toList();
        List<List<String>> partition = Lists.partition(toInsert, 5);
        for (List<String> fiveModelNoList : partition) {
            LimiterHelper.limitPoisonItem();
            Thread.ofVirtual().name("poison-api").start(() -> {
                try {
                    List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(fiveModelNoList);
                    SqlHelper.batch(poisonItemDOS, item -> poisonItemMapper.insertIgnore(item));
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            });
        }
    }

    @SneakyThrows
    @Task(platform = TaskDO.PlatformEnum.POISON, taskType = TaskDO.TaskTypeEnum.REFRESH_ALL_PRICES, operateStatus = TaskDO.OperateStatusEnum.SYSTEM)
    public void refreshAllPrice() {
        // 互斥操作，一次只能进行一次全量价格更新
        LockHelper.lockPoisonPrice();
        // 拿到所有要查询的货号
        List<String> modelNos = shoesService.queryAllModels();
        log.info("start refreshAllPrice, cnt:{}", modelNos.size());
        List<List<String>> partition = Lists.partition(modelNos, 20);
        int i = 1;
        for (List<String> modelNumbers : partition) {
            List<PoisonPriceDO> poisonPriceDOList = poisonClient.batchQueryPrice(modelNumbers);
//            priceManager.putModelNoPrice(modelNumber, poisonPriceDOList);
            log.info("finish refreshAllPrice-{}, cnt:{}", i++, modelNumbers.size());
        }
        LockHelper.unlockPoisonPrice();
    }

    /**
     * 将db里的得物价格导入到缓存中
     */
    public void importPriceToCache() {
        poisonPriceMapper.deleteExpire();
        List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectList(new QueryWrapper<>());
        Map<String, Map<String, PoisonPriceDO>> toImportMap = new HashMap<>();
        for (PoisonPriceDO poisonPriceDO : poisonPriceDOList) {
            String modelNo = poisonPriceDO.getModelNo();
            String euSize = poisonPriceDO.getEuSize();
            toImportMap.computeIfAbsent(modelNo, _ -> new HashMap<>()).put(euSize, poisonPriceDO);
        }
        priceManager.importPrice(toImportMap);
        log.info("finish importPriceToCache, size:{}", toImportMap.size());
    }
}
