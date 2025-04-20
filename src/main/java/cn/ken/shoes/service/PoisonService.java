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
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
    private KickScrewItemMapper kickScrewItemMapper;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    @Resource
    private PriceManager priceManager;

    public PoisonItemDO selectItemByModelNo(String modelNo) {
        PoisonItemDO poisonItemDO = poisonItemMapper.selectByArticleNumber(modelNo);
        if (poisonItemDO == null) {
            List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(List.of(modelNo));
            poisonItemDO = CollectionUtils.isEmpty(poisonItemDOS) ? null : poisonItemDOS.getFirst();
            PoisonItemDO finalPoisonItemDO = poisonItemDO;
            Thread.startVirtualThread(() -> poisonItemMapper.insertIgnore(finalPoisonItemDO));
        }
        return poisonItemDO;
    }

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
        List<String> modelNos = getAllModelNos();
        List<List<String>> partition = Lists.partition(modelNos, 20);
        CountDownLatch insertLatch = new CountDownLatch(partition.size());
        for (List<String> modelNumbers : partition) {
            CopyOnWriteArrayList<PoisonPriceDO> toInsert = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(modelNumbers.size());
            // 查询价格
            for (String modelNumber : modelNumbers) {
                Thread.startVirtualThread(() -> {
                    try {
                        List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceByModelNo(modelNumber);
                        toInsert.addAll(poisonPriceDOList);
                        priceManager.putModelNoPrice(modelNumber, poisonPriceDOList);
                    } catch (Exception e) {
                        log.error(e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            System.out.println(STR."allNo:\{modelNumbers.size()}, resultNum:\{toInsert.stream().collect(Collectors.toMap(PoisonPriceDO::getPrice, Function.identity())).keySet().size()}");
            // 插入价格
            Thread.startVirtualThread(() -> {
                try {
                    SqlHelper.batch(toInsert, item -> poisonPriceMapper.insertOverwrite(item));
                } finally {
                    insertLatch.countDown();
                }
            });
        }
        insertLatch.await();
        LockHelper.unlockPoisonPrice();
    }

    private List<String> getAllModelNos() {
        List<String> modelNos = new ArrayList<>();
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        List<String> mustCrawlModelNos = mustCrawlMapper.selectAllModelNos();
        modelNos.addAll(hotModelNos);
        modelNos.addAll(mustCrawlModelNos);
        return modelNos;
    }

    /**
     * 将db里的得物价格导入到缓存中
     */
    public void importPriceToCache() {
        List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectList(new QueryWrapper<>());
        Map<String, Map<String, Integer>> toImportMap = new HashMap<>();
        for (PoisonPriceDO poisonPriceDO : poisonPriceDOList) {
            String modelNo = poisonPriceDO.getModelNo();
            Integer price = poisonPriceDO.getPrice();
            String euSize = poisonPriceDO.getEuSize();
            toImportMap.computeIfAbsent(modelNo, k -> new HashMap<>()).put(euSize, price);
        }
        priceManager.importPrice(toImportMap);
    }
}
