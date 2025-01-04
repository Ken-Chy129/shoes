package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class KickScrewService {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    public int scratchAndSaveCategories() {
        long startTime = System.currentTimeMillis();
        log.info("scratchAndSaveCategories start");
        KickScrewCategory kickScrewCategory = kickScrewClient.queryCategory();
        if (kickScrewCategory == null || kickScrewCategory.getBrand() == null) {
            log.error("queryCategory no result");
            return -1;
        }
        Map<String, Integer> brandCntMap = kickScrewCategory.getBrand();
        List<BrandDO> brandDOList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : brandCntMap.entrySet()) {
            String brandName = entry.getKey();
            Integer cnt = entry.getValue();
            BrandDO brandDO = new BrandDO();
            brandDO.setName(brandName);
            brandDO.setCnt(cnt);
            brandDOList.add(brandDO);
        }
        brandMapper.deleteAll();
        brandMapper.insert(brandDOList);
        int itemCnt = brandCntMap.values().stream().mapToInt(Integer::intValue).sum();
        log.info("scratchAndSaveCategories end, categoryCnt:{}, itemCnt:{}, cost:{}",
                brandDOList.size(),
                itemCnt,
                System.currentTimeMillis() - startTime);
        return itemCnt;
    }

    public void scratchAndSaveItems() {
        kickScrewItemMapper.delete(null);

        int itemCnt = scratchAndSaveCategories();
        List<BrandDO> brandDOList = brandMapper.selectList(new QueryWrapper<>());

        long allStartTime = System.currentTimeMillis();
        log.info("scratchAndSaveItems start, brandCnt:{}, itemCnt:{}", brandDOList.size(), itemCnt);
        CountDownLatch brandLatch = new CountDownLatch(brandDOList.size());
        Map<String, Map<Integer, List<KickScrewItemDO>>> allItemsMap = new HashMap<>();
        AtomicInteger finishCnt = new AtomicInteger(0);
        for (BrandDO brandDO : brandDOList) {
            Thread.ofVirtual().name(brandDO.getName()).start(() -> {
                long brandStartTime = System.currentTimeMillis();
                try {
                    Map<Integer, List<KickScrewItemDO>> brandItemsMap = new HashMap<>();
                    String brand = brandDO.getName();
                    Integer cnt = brandDO.getCnt();
                    // 根据品牌的商品数量计算请求的分页次数
                    int page = (int) Math.ceil(cnt / (double) KickScrewConfig.PAGE_SIZE);
//                    CountDownLatch pageLatch = new CountDownLatch(page);
                    // 查询品牌下所有商品
                    for (int i = 1; i <= page; i++) {
                        final int pageIndex = i;
//                        Thread.ofVirtual().name("brandItems-" + brand).start(() -> {
//                            try {
                                List<KickScrewItemDO> brandItems = kickScrewClient.queryItemByBrand(brand, pageIndex);
                                brandItemsMap.put(pageIndex, brandItems);
//                            } finally {
//                                pageLatch.countDown();
//                            }
//                        });
                    }
//                    pageLatch.await();
                    allItemsMap.put(brand, brandItemsMap);
                } catch (Exception e) {
                    log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brandDO.getName(), e.getMessage());
                } finally {
                    brandLatch.countDown();
                    log.info("finishScratch brand:{}, idx:{}, cnt:{}, cost:{}", brandDO.getName(), finishCnt.incrementAndGet(), brandDO.getCnt(), System.currentTimeMillis() - brandStartTime);
                }
            });
        }
        try {
            brandLatch.await();
        } catch (InterruptedException e) {
            log.error("scratchAndSaveItems error, msg:{}", e.getMessage());
        }
        log.info("scratchItems end, cost:{}", System.currentTimeMillis() - allStartTime);
        batchInsertItems(allItemsMap);
    }

    private void batchInsertItems(Map<String, Map<Integer, List<KickScrewItemDO>>> allItemsMap) {
        long allStartTime = System.currentTimeMillis();
        log.info("batchInsertItems start");
        for (Map.Entry<String, Map<Integer, List<KickScrewItemDO>>> entry : allItemsMap.entrySet()) {
            String brand = entry.getKey();
            long startTime = System.currentTimeMillis();
            Map<Integer, List<KickScrewItemDO>> brandItemsMap = entry.getValue();
            List<KickScrewItemDO> brandItems = brandItemsMap.values().stream().flatMap(List::stream).toList();
            try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false);) {
                for (KickScrewItemDO brandItem : brandItems) {
                    kickScrewItemMapper.insertIgnore(brandItem);
                }
                sqlSession.commit();
            }
            log.info("batchInsertItems, brand:{}, cnt:{}, cost:{}", brand, brandItems.size(), System.currentTimeMillis() - startTime);
        }
        log.info("batchInsertItems end, cost:{}", System.currentTimeMillis() - allStartTime);
    }
}
