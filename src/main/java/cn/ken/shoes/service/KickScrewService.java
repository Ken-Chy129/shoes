package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.ItemSizePriceMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.ItemSizePriceDO;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
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
    private ItemSizePriceMapper itemSizePriceMapper;

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    /**
     * 目录数据爬取并保存品牌类型和数量
     * @return 返回商品总数
     */
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

    /**
     * 爬取kc平台商品并保存（根据品牌进行爬取）
     */
    public void scratchAndSaveItems() {
        kickScrewItemMapper.deleteAll();

        int itemCnt = scratchAndSaveCategories();
        List<String> brandList = brandMapper.selectBrandNames();

        long allStartTime = System.currentTimeMillis();
        log.info("scratchAndSaveItems start, brandCnt:{}, itemCnt:{}", brandList.size(), itemCnt);
        CountDownLatch brandLatch = new CountDownLatch(brandList.size());
        Map<String, Map<Integer, List<KickScrewItemDO>>> allItemsMap = new HashMap<>();
        AtomicInteger finishCnt = new AtomicInteger(0);
        for (String brand : brandList) {
//            Thread.ofVirtual().name(brandDO.getName()).start(() -> {
                long brandStartTime = System.currentTimeMillis();
                try {
                    Map<Integer, List<KickScrewItemDO>> brandItemsMap = new HashMap<>();
                    // 查询品牌下所有商品
                    Integer page = kickScrewClient.queryBrandItemPage(brand);
                    CountDownLatch pageLatch = new CountDownLatch(page);
                    for (int i = 1; i <= page; i++) {
                        final int pageIndex = i;
                        Thread.ofVirtual().name(brand + ":" + pageIndex).start(() -> {
                            try {
                                List<KickScrewItemDO> brandItems = kickScrewClient.queryItemByBrand(brand, pageIndex);
                                brandItemsMap.put(pageIndex, brandItems);
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            } finally {
                                pageLatch.countDown();
                            }
                        });
                    }
                    pageLatch.await();
                    allItemsMap.put(brand, brandItemsMap);
                } catch (Exception e) {
                    log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brand, e.getMessage());
                } finally {
                    brandLatch.countDown();
                    log.info("finishScratch brand:{}, idx:{}, cnt:{}, cost:{}", brand, finishCnt.incrementAndGet(), brand, System.currentTimeMillis() - brandStartTime);
                }
//            });
        }
        try {
            brandLatch.await();
        } catch (InterruptedException e) {
            log.error("scratchAndSaveItems error, msg:{}", e.getMessage());
        }
        log.info("refreshPoisonItems end, cost:{}", System.currentTimeMillis() - allStartTime);
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

    public void scratchItemPrices() {
        List<String> brandList = brandMapper.selectBrandNames();
        for (String brand : brandList) {
            List<KickScrewItemDO> brandItems = kickScrewItemMapper.selectListByBrand(brand);
            Map<String, List<ItemSizePriceDO>> itemSizePricesMap = new HashMap<>();

            for (KickScrewItemDO brandItem : brandItems) {
                Thread.ofVirtual().name("scratchItemPrices:" + brand).start(() -> {
                    String modelNo = brandItem.getModelNo();
                    String handle = brandItem.getHandle();
                    List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(handle);
                    kickScrewSizePrices.stream()
                            .map(this::toSizePrice)
                            .forEach(itemSizePriceDO -> {
                                itemSizePriceDO.setModelNumber(modelNo);
                                itemSizePriceDO.setCreateTime(new Date());
                                itemSizePricesMap.computeIfAbsent(modelNo, k -> new ArrayList<>()).add(itemSizePriceDO);
                            });
                });
            }
            Thread.ofVirtual().name("sql").start(() -> itemSizePriceMapper.insert(itemSizePricesMap.values().stream().flatMap(List::stream).toList()));
        }
    }

    private ItemSizePriceDO toSizePrice(KickScrewSizePrice kickScrewSizePrice) {
        ItemSizePriceDO itemSizePriceDO = new ItemSizePriceDO();
        Map<String, String> price = kickScrewSizePrice.getPrice();
        itemSizePriceDO.setKickScrewPrice(BigDecimal.valueOf(Double.parseDouble(price.get("amount"))));
        String title = kickScrewSizePrice.getTitle();
        List<String> sizeList = Arrays.stream(title.split("/")).map(String::trim).toList();
        for (String size : sizeList) {
            String[] split = size.split(" ");
            if (split.length < 2) {
                continue;
            }
            String sizeType = split[0];
            String value = split.length > 2 ? split[2] : split[1];
            switch (SizeEnum.from(sizeType)) {
                case MEN_US -> itemSizePriceDO.setMenUSSize(value);
                case WOMAN_US -> itemSizePriceDO.setWomenUSSize(value);
                case EU -> itemSizePriceDO.setEuSize(value);
                case UK -> itemSizePriceDO.setUkSize(value);
                case JP -> itemSizePriceDO.setJpSize(value);
            }
        }
        return itemSizePriceDO;
    }

}
