package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.entity.PoisonItemDO;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

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
    private KickScrewItemMapper kickScrewItemMapper;

    public void refreshPoisonItems() throws InterruptedException {
        List<String> brandList = brandMapper.selectBrandNames();
        RateLimiter rateLimiter = RateLimiter.create(15);
        int total = 0;
        for (String brand : brandList) {
            List<String> brandModelNoList = kickScrewItemMapper.selectListByBrand(brand).stream().map(KickScrewItemDO::getModelNo).collect(Collectors.toList());
            // 已存在的商品不需要再查询
            List<String> existBrandModelNoList = poisonItemMapper.selectModelNoByKcBrand(brand);
            brandModelNoList.removeAll(existBrandModelNoList);
            List<PoisonItemDO> brandItems = new CopyOnWriteArrayList<>();
            List<List<String>> partition = Lists.partition(brandModelNoList, 5);
            CountDownLatch latch = new CountDownLatch(partition.size());
            for (List<String> fiveModelNoList : partition) {
                double waitTime = rateLimiter.acquire();
                log.info("waitTime:{}", waitTime);
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
            for (PoisonItemDO brandItem : brandItems) {
                brandItem.setKcBrand(brand);
            }
            Thread.ofVirtual().name("sql").start(() -> poisonItemMapper.insert(brandItems));
            log.info("refreshPoisonItems finish, brand:{}, cnt:{}", brand, brandItems.size());
            total += brandItems.size();
        }
        log.info("refreshPoisonItems, total:{}", total);
        System.out.println("finish");
    }

//    public void queryAndSaveSpuIds(List<String> modelNumbers) throws InterruptedException {
//        RateLimiter rateLimiter = RateLimiter.create(15);
//        List<PoisonItemDO> brandItems = new CopyOnWriteArrayList<>();
//        List<List<String>> partition = Lists.partition(modelNumbers, 5);
//        CountDownLatch latch = new CountDownLatch(partition.size());
//        for (List<String> fiveModelNoList : partition) {
//            rateLimiter.acquire();
//            Thread.ofVirtual().name("poison-api").start(() -> {
//                try {
//                    List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(fiveModelNoList);
//                    if (CollectionUtils.isEmpty(poisonItemDOS)) {
//                        return;
//                    }
//                    brandItems.addAll(poisonItemDOS);
//                } catch (Exception e) {
//                    log.error(e.getMessage(), e);
//                } finally {
//                    latch.countDown();
//                }
//            });
//        }
//        latch.await();
//        Thread.ofVirtual().name("sql").start(() -> poisonItemMapper.insert(brandItems));
//        log.info("refreshPoisonItems finish, brand:{}, cnt:{}", brand, brandItems.size());
//        total += brandItems.size();
//    }

}
