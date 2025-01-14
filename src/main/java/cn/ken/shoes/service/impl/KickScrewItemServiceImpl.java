package cn.ken.shoes.service.impl;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.KickScrewPriceMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewItemRequest;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.util.ShoesUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service("kickScrewItemService")
public class KickScrewItemServiceImpl implements ItemService {

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    @Resource
    private KickScrewPriceMapper kickScrewPriceMapper;

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    @Override
    public List<BrandDO> scratchBrands() {
        return List.of();
    }

    public List<BrandDO> selectBrands() {
        return brandMapper.selectList(new QueryWrapper<>());
    }

    @Override
    public void refreshAllItems() {
        kickScrewItemMapper.deleteAll();

        List<BrandDO> brandList = selectBrands();
        
        AtomicInteger finishCnt = new AtomicInteger(0);
        int brandItemCnt;
        for (BrandDO brandDO : brandList) {
            String brand = brandDO.getName();
            long brandStartTime = System.currentTimeMillis();
            brandItemCnt = 0;
            try {
                Map<String, List<KickScrewItemDO>> releaseYearItemsMap = new HashMap<>();
                for (Integer releaseYear : ItemQueryConfig.ALL_RELEASE_YEARS) {
                    // 查询品牌下所有商品
                    KickScrewAlgoliaRequest algoliaRequest = new KickScrewAlgoliaRequest();
                    algoliaRequest.setBrands(List.of(brand));
                    algoliaRequest.setReleaseYears(List.of(releaseYear));
                    Integer page = kickScrewClient.queryBrandItemPageV2(algoliaRequest);
                    CountDownLatch pageLatch = new CountDownLatch(page);
                    for (int i = 0; i < page; i++) {
                        final int pageIndex = i;
                        Thread.ofVirtual().name(brand + ":" + pageIndex).start(() -> {
                            try {
                                algoliaRequest.setPageIndex(pageIndex);
                                List<KickScrewItemDO> brandItems = kickScrewClient.queryItemByBrandV2(algoliaRequest);
                                releaseYearItemsMap.put(String.valueOf(releaseYear) + pageIndex, brandItems);
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            } finally {
                                pageLatch.countDown();
                            }
                        });
                    }
                    pageLatch.await();
                }
                brandItemCnt = releaseYearItemsMap.values().stream().mapToInt(List::size).sum();
                Thread.ofVirtual().start(() -> batchInsertItems(releaseYearItemsMap));
            } catch (Exception e) {
                log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brand, e.getMessage());
            } finally {
                log.info("finishScratch brand:{}, idx:{}, cnt:{}, cost:{}", brand, finishCnt.incrementAndGet(), brandItemCnt, System.currentTimeMillis() - brandStartTime);
            }
        }
    }

    private void batchInsertItems(Map<String, List<KickScrewItemDO>> itemMap) {
        List<KickScrewItemDO> brandItems = itemMap.values().stream().flatMap(List::stream).toList();
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            for (KickScrewItemDO brandItem : brandItems) {
                kickScrewItemMapper.insertIgnore(brandItem);
            }
            sqlSession.commit();
        }
    }

    @Override
    public List<String> selectItemsByCondition() {
        List<KickScrewItemDO> kickScrewItemDOS = kickScrewItemMapper.selectModelNoByCondition(new KickScrewItemRequest());
        return null;
    }

    @Override
    public void refreshAllPrices() {
        long currentTimeMillis = System.currentTimeMillis();
        KickScrewItemRequest kickScrewItemRequest = new KickScrewItemRequest();
        kickScrewItemRequest.setBrands(List.of("New Balance"));
        Integer count = kickScrewItemMapper.count(new KickScrewItemRequest());
        int page = (int) Math.ceil(count / 1000.0);
        kickScrewItemRequest.setPageSize(1000);
        for (int i = 1; i <= page; i++) {
            kickScrewItemRequest.setPageIndex(i);
            List<KickScrewItemDO> itemDOList = kickScrewItemMapper.selectModelNoByCondition(kickScrewItemRequest);
            CountDownLatch latch = new CountDownLatch(itemDOList.size());
            List<KickScrewPriceDO> toInsert = new CopyOnWriteArrayList<>();
            long pageStart = System.currentTimeMillis();
            for (KickScrewItemDO itemDO : itemDOList) {
                Thread.ofVirtual().name("refreshAllPrices:" + itemDO.getModelNo()).start(() -> {
                    try {
                        List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(itemDO.getHandle());
                        String modelNo = itemDO.getModelNo();
                        for (KickScrewSizePrice kickScrewSizePrice : kickScrewSizePrices) {
                            String title = kickScrewSizePrice.getTitle();
                            String euSize = ShoesUtil.getEuSizeFromKickScrew(title);
                            KickScrewPriceDO kickScrewPriceDO = new KickScrewPriceDO();
                            kickScrewPriceDO.setModelNo(modelNo);
                            kickScrewPriceDO.setEuSize(euSize);
                            Map<String, String> price = kickScrewSizePrice.getPrice();
                            kickScrewPriceDO.setPrice(kickScrewSizePrice.isAvailableForSale() ? BigDecimal.valueOf(Double.parseDouble(price.get("amount"))) : BigDecimal.valueOf(-1));
                            toInsert.add(kickScrewPriceDO);
                        }
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            Thread.ofVirtual().start(() -> kickScrewPriceMapper.insert(toInsert));
            log.info("page refresh end, cost:{}, pageIndex:{}", System.currentTimeMillis() - pageStart, i);
        }
        log.info("refreshAllPrices end, cost:{}", System.currentTimeMillis() - currentTimeMillis);
    }

    @Override
    public void changePrice() {
        
    }
}
