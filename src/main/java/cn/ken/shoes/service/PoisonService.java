package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.util.AsyncUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.SqlHelper;
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
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    @Resource
    private TaskService taskService;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    public void refreshPoisonItems() {
        int total = 0;
        for (Integer releaseYear : ItemQueryConfig.ALL_RELEASE_YEARS) {
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

    /**
     * 增量更新得物商品
     */
    public void updatePoisonItems(List<String> modelNumbers) {
        List<String> existItems = poisonItemMapper.selectExistModelNos(modelNumbers);
        List<String> toInsert = modelNumbers.stream().filter(modelNumber -> !existItems.contains(modelNumber)).toList();
        RateLimiter rateLimiter = RateLimiter.create(10);
        List<List<String>> partition = Lists.partition(toInsert, 5);
        for (List<String> fiveModelNoList : partition) {
            rateLimiter.acquire();
            Thread.ofVirtual().name("poison-api").start(() -> {
                try {
                    List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(fiveModelNoList);
                    if (CollectionUtils.isEmpty(poisonItemDOS)) {
                        return;
                    }
                    SqlHelper.batch(poisonItemDOS, item -> poisonItemMapper.insertIgnore(item));
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            });
        }
    }

    public void refreshPriceByModelNos(List<String> modelNos, boolean overwriteOld) {
        List<PoisonItemDO> poisonItemDOS = poisonItemMapper.selectSpuIdByModelNos(modelNos);
        for (List<PoisonItemDO> itemDOS : Lists.partition(poisonItemDOS, 20)) {
            try {
                CopyOnWriteArrayList<PoisonPriceDO> toInsert = new CopyOnWriteArrayList<>();
                CountDownLatch latch = new CountDownLatch(itemDOS.size());
                for (PoisonItemDO itemDO : itemDOS) {
                    Thread.startVirtualThread(() -> {
                        try {
                            if (!overwriteOld) {
                                List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectListByModelNos(Set.of(itemDO.getTitle()));
                                if (CollectionUtils.isNotEmpty(poisonPriceDOList)) {
                                    return;
                                }
                            }
                            LimiterHelper.limitPoisonPrice();
                            List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpuV2(itemDO.getArticleNumber(), itemDO.getSpuId());
//                            List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceV3(itemDO.getSpuId());
                            Optional.ofNullable(poisonPriceDOList).ifPresent(toInsert::addAll);
                        } catch (Exception e) {
                            log.error(e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                Thread.startVirtualThread(() -> SqlHelper.batch(toInsert, item -> poisonPriceMapper.insertOverwrite(item)));
            } catch (Exception e) {
                log.error("refreshPriceByModelNos error, msg:{}", e.getMessage(), e);
            }
        }
    }

    public List<String> getAllModelNos() {
        List<String> modelNos = new ArrayList<>();
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        List<String> mustCrawlModelNos = mustCrawlMapper.queryByPlatformList("kc");
        modelNos.addAll(hotModelNos);
        modelNos.addAll(mustCrawlModelNos);
        return modelNos;
    }

    public void refreshPrice(boolean overwriteOld) {
        List<String> allModelNos = getAllModelNos();
        updatePoisonItems(allModelNos);
        refreshPriceByModelNos(allModelNos, overwriteOld);
    }

}
