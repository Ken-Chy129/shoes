package cn.ken.shoes.service;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.util.AsyncUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.LockHelper;
import cn.ken.shoes.util.SqlHelper;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

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
     * 增量更新得物商品
     */
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
        // 将得物没有的商品先进行增量更新
        updatePoisonItems(modelNos);
        // 查询上一个价格版本号
        int oldVersion = Optional.ofNullable(poisonPriceMapper.getMaxVersion()).orElse(-1);
        int newVersion = oldVersion + 1;
        List<PoisonItemDO> poisonItemDOS = poisonItemMapper.selectSpuIdByModelNos(modelNos);
        List<List<PoisonItemDO>> partition = Lists.partition(poisonItemDOS, 20);
        CountDownLatch insertLatch = new CountDownLatch(partition.size());
        for (List<PoisonItemDO> itemDOS : partition) {
            CopyOnWriteArrayList<PoisonPriceDO> toInsert = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(itemDOS.size());
            // 查询价格
            for (PoisonItemDO itemDO : itemDOS) {
                Thread.startVirtualThread(() -> {
                    try {
                        LimiterHelper.limitPoisonPrice();
                        List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpuV2(itemDO.getArticleNumber(), itemDO.getSpuId());
                        poisonPriceDOList.forEach(poisonPriceDO -> poisonPriceDO.setVersion(newVersion));
                        toInsert.addAll(poisonPriceDOList);
                    } catch (Exception e) {
                        log.error(e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            // 插入价格
            Thread.startVirtualThread(() -> {
                try {
                    SqlHelper.batch(toInsert, item -> poisonPriceMapper.insertOverwrite(item));
                } finally {
                    insertLatch.countDown();
                }
            });
        }
        // 等待所有的价格插入完成之后删除旧版本的数据
        insertLatch.await();
        poisonPriceMapper.deleteOldVersion(oldVersion);
        LockHelper.unlockPoisonPrice();
    }

    public void refreshPrice() {
        List<String> allModelNos = getAllModelNos();
        updatePoisonItems(allModelNos);
    }

    private List<String> getAllModelNos() {
        List<String> modelNos = new ArrayList<>();
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        List<String> mustCrawlModelNos = mustCrawlMapper.queryByPlatformList("kc");
        modelNos.addAll(hotModelNos);
        modelNos.addAll(mustCrawlModelNos);
        return modelNos;
    }
}
