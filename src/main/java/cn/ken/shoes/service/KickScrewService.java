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
import com.alibaba.fastjson.JSONObject;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    public void scratchAndSaveItemPrices() throws InterruptedException {
//        List<String> brandList = brandMapper.selectBrandNames();
        // todo：版本查询
        List<String> brandList = List.of("NIKE");
        RateLimiter rateLimiter = RateLimiter.create(20);
        for (String brand : brandList) {
            long startTime = System.currentTimeMillis();
            List<KickScrewItemDO> brandItems = kickScrewItemMapper.selectListByBrand(brand);
            Map<String, List<ItemSizePriceDO>> itemSizePricesMap = new HashMap<>();
            CountDownLatch brandLatch = new CountDownLatch(brandItems.size());
            for (KickScrewItemDO brandItem : brandItems) {
                log.info("waitTime:{}", rateLimiter.acquire());
                Thread.ofVirtual().name("scratchAndSaveItemPrices:" + brand).start(() -> {
                    try {
                        String modelNo = brandItem.getModelNo();
                        String handle = brandItem.getHandle();
                        List<Map<String, String>> sizeChart = kickScrewClient.queryItemSizeChart(brand, modelNo);
                        List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(handle);
                        if (sizeChart.size() != kickScrewSizePrices.size()) {
                            log.error("尺码表与尺码价格表数量不匹配！, brand:{}, modelNo:{}, handle:{}", brand, modelNo, handle);
                            for (KickScrewSizePrice kickScrewSizePrice : kickScrewSizePrices) {
                                String title = kickScrewSizePrice.getTitle();
                                String size = Arrays.stream(title.split("/"))
                                        .filter(labelSize -> labelSize.contains(SizeEnum.EU.getCode()))
                                        .findFirst()
                                        .map(labelSize -> labelSize.replace(SizeEnum.EU.getCode(), ""))
                                        .map(String::trim)
                                        .orElse(null);
                                Map<String, String> labelSizeMap = sizeChart.stream()
                                        .filter(map -> map.get(SizeEnum.EU.getCode()).equals(size))
                                        .findFirst()
                                        .orElse(null);
                                if (labelSizeMap == null) {
                                    continue;
                                }
                                ItemSizePriceDO itemSizePriceDO = buildItemSizePriceDO(brand, modelNo, handle, kickScrewSizePrice, labelSizeMap);
                                itemSizePricesMap.computeIfAbsent(modelNo, k -> new ArrayList<>()).add(itemSizePriceDO);
                            }
                        }  else {
                            for (int i = 0; i < sizeChart.size(); i++) {
                                KickScrewSizePrice kickScrewSizePrice = kickScrewSizePrices.get(i);
                                Map<String, String> labelSizeMap = sizeChart.get(i);
                                ItemSizePriceDO itemSizePriceDO = buildItemSizePriceDO(brand, modelNo, handle, kickScrewSizePrice, labelSizeMap);

                                // todo：查询得物价格，注意考虑得物尺码 ⅔，在kc是.67, ⅓是.33
                                itemSizePricesMap.computeIfAbsent(modelNo, k -> new ArrayList<>()).add(itemSizePriceDO);
                            }
                        }
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    } finally {
                        brandLatch.countDown();
                    }
                });
            }
            brandLatch.await();
            List<ItemSizePriceDO> itemSizePriceDOS = itemSizePricesMap.values().stream().flatMap(List::stream).toList();
            log.info("scratch finish, brand:{}, itemCnt:{}, sizeCnt:{}, cost:{}", brand, brandItems.size(), itemSizePriceDOS.size(), System.currentTimeMillis() - startTime);
            Thread.ofVirtual().name("sql").start(() -> itemSizePriceMapper.insert(itemSizePriceDOS));
        }
        System.out.println("finish");
    }


    private ItemSizePriceDO buildItemSizePriceDO(String brand, String modelNo, String handle, KickScrewSizePrice kickScrewSizePrice, Map<String, String> labelSizeMap) {
        Map<String, String> price = kickScrewSizePrice.getPrice();
        BigDecimal amount = BigDecimal.valueOf(Double.parseDouble(price.get("amount")));
        ItemSizePriceDO itemSizePriceDO = new ItemSizePriceDO();
        itemSizePriceDO.setCreateTime(new Date());
        itemSizePriceDO.setBrand(brand);
        itemSizePriceDO.setModelNumber(modelNo);
        itemSizePriceDO.setKickScrewPrice(amount);

        itemSizePriceDO.setEuSize(labelSizeMap.get(SizeEnum.EU.getCode()));
        itemSizePriceDO.setMenUSSize(labelSizeMap.get(SizeEnum.MEN_US.getCode()));
        itemSizePriceDO.setWomenUSSize(labelSizeMap.get(SizeEnum.WOMAN_US.getCode()));
        itemSizePriceDO.setUsSize(Optional.ofNullable(labelSizeMap.get(SizeEnum.US.getCode()))
                .orElse(Optional.ofNullable(itemSizePriceDO.getMenUSSize())
                        .orElse(itemSizePriceDO.getWomenUSSize())
                )
        );
        itemSizePriceDO.setUkSize(labelSizeMap.get(SizeEnum.UK.getCode()));
        itemSizePriceDO.setCmSize(labelSizeMap.get(SizeEnum.CM.getCode()));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("labelSize", labelSizeMap);
        attributes.put("title", kickScrewSizePrice.getTitle());
        attributes.put("currencyCode", price.get("currencyCode"));
        attributes.put("handle", handle);
        itemSizePriceDO.setAttributes(JSONObject.toJSONString(attributes));
        return itemSizePriceDO;
    }
}
