package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.util.AsyncUtil;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

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
    private BrandMapper brandMapper;

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    public void refreshPoisonItems() throws InterruptedException {
        RateLimiter rateLimiter = RateLimiter.create(15);
        int total = 0;
        for (Integer releaseYear : ItemQueryConfig.ALL_RELEASE_YEARS) {
            final List<String> modelNoList = new ArrayList<>(), existModelNoList = new ArrayList<>();
            AsyncUtil.awaitTasks(List.of(
                () -> modelNoList.addAll(kickScrewItemMapper.selectModelNoByReleaseYear(releaseYear)),
                () -> existModelNoList.addAll(poisonItemMapper.selectModelNoByReleaseYear(releaseYear))
            ));
            modelNoList.removeAll(existModelNoList);
            List<PoisonItemDO> brandItems = new CopyOnWriteArrayList<>();
            AsyncUtil.awaitTasks(Lists.partition(modelNoList, 5).stream().map(fiveModelNoList -> (Runnable) () -> {
                rateLimiter.acquire();
                List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(fiveModelNoList);
                if (CollectionUtils.isEmpty(poisonItemDOS)) {
                    return;
                }
                brandItems.addAll(poisonItemDOS);
            }).toList());
            Thread.ofVirtual().name("sql").start(() -> poisonItemMapper.insert(brandItems));
            log.info("refreshPoisonItems finish, releaseYear:{}, cnt:{}", releaseYear, brandItems.size());
            total += brandItems.size();
        }
        log.info("refreshPoisonItems, total:{}", total);
        System.out.println("finish");
    }

    public List<Pair<String, Long>> queryAndSaveSpuIds(List<String> modelNumbers) throws InterruptedException {
        RateLimiter rateLimiter = RateLimiter.create(15);
        List<PoisonItemDO> brandItems = new CopyOnWriteArrayList<>();
        List<List<String>> partition = Lists.partition(modelNumbers, 5);
        CountDownLatch latch = new CountDownLatch(partition.size());
        for (List<String> fiveModelNoList : partition) {
            rateLimiter.acquire();
            Thread.ofVirtual().name("poison-api").start(() -> {
                try {
                    List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(fiveModelNoList);
                    if (CollectionUtils.isEmpty(poisonItemDOS)) {
                        return;
                    }
                    brandItems.addAll(poisonItemDOS);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        Thread.ofVirtual().name("sql").start(() -> poisonItemMapper.insert(brandItems));
        return null;
    }

    public void refreshPrice(List<Pair<String, Long>> modelNoSpuIdList) {
        for (Pair<String, Long> pair : modelNoSpuIdList) {
            String modelNo = pair.getKey();
            Long spuId = pair.getValue();
            Map<String, Map<PriceEnum, Integer>> sizePriceMap = poisonClient.queryPriceBySpu(spuId);
            if (sizePriceMap == null) {
                continue;
            }
            List<PoisonPriceDO> toInsert = new ArrayList<>();
            for (Map.Entry<String, Map<PriceEnum, Integer>> entry : sizePriceMap.entrySet()) {
                Map<PriceEnum, Integer> map = entry.getValue();
                PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                poisonPriceDO.setModelNo(modelNo);
                poisonPriceDO.setEuSize(entry.getKey());
                poisonPriceDO.setNormalPrice(map.get(PriceEnum.NORMAL));
                poisonPriceDO.setNormalPrice(map.get(PriceEnum.LIGHTNING));
                toInsert.add(poisonPriceDO);
            }
            Thread.ofVirtual().start(() -> poisonPriceMapper.insert(toInsert));
        }

    }

}
