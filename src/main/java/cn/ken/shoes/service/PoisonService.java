package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.util.AsyncUtil;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Callable;
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

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    public void refreshPoisonItems() {
        int total = 0;
//        for (Integer releaseYear : ItemQueryConfig.ALL_RELEASE_YEARS) {
        for (Integer releaseYear : List.of(2024)) {
            try {
                List<List<String>> result = AsyncUtil.runTasksWithResult(List.of(
                        () -> kickScrewItemMapper.selectModelNoByReleaseYear(releaseYear),
                        () -> poisonItemMapper.selectModelNoByReleaseYear(releaseYear)
                ));
                List<String> modelNoList = result.getFirst(), existModelNoList = result.get(1);
                modelNoList.removeAll(existModelNoList);
                List<Callable<List<PoisonItemDO>>> suppliers = Lists.partition(modelNoList, 5).stream()
                        .map(fiveModelNoList -> (Callable<List<PoisonItemDO>>) () -> poisonClient.queryItemByModelNos(fiveModelNoList))
                        .toList();
                List<PoisonItemDO> items = AsyncUtil.runTasksWithResult(suppliers, 15).stream()
                        .filter(CollectionUtils::isNotEmpty)
                        .flatMap(List::stream)
                        .toList();
                items.forEach(item -> item.setReleaseYear(releaseYear));
                AsyncUtil.runTasks(List.of(() -> batchInsertItems(items)));
                log.info("refreshPoisonItems finish, releaseYear:{}, cnt:{}", releaseYear, items.size());
                total += items.size();
            } catch (Exception e) {
                log.error("refreshPoisonItems error", e);
            }
        }
        log.info("refreshPoisonItems finish, cnt:{}", total);
    }

    private void batchInsertItems(List<PoisonItemDO> items) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            for (PoisonItemDO item : items) {
                poisonItemMapper.insertIgnore(item);
            }
            sqlSession.commit();
        }
        log.info("batchInsertItems success");
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
