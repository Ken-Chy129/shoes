package cn.ken.shoes.service.impl;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.KickScrewPriceMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewItemRequest;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.kickscrew.KickScrewUploadItem;
import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.util.AsyncUtil;
import cn.ken.shoes.util.ShoesUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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
                Thread.ofVirtual().start(() -> batchInsertItems(releaseYearItemsMap.values().stream().flatMap(List::stream).toList()));
            } catch (Exception e) {
                log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brand, e.getMessage());
            } finally {
                log.info("finishScratch brand:{}, idx:{}, cnt:{}, cost:{}", brand, finishCnt.incrementAndGet(), brandItemCnt, System.currentTimeMillis() - brandStartTime);
            }
        }
    }

    @Override
    public void refreshIncrementalItems() {
        try {
            Integer recentYear = ItemQueryConfig.ALL_RELEASE_YEARS.getFirst();
            // 查询品牌下所有商品
            KickScrewAlgoliaRequest algoliaRequest = new KickScrewAlgoliaRequest();
            algoliaRequest.setReleaseYears(List.of(recentYear));
            Integer page = kickScrewClient.queryBrandItemPageV2(algoliaRequest);
            List<List<KickScrewItemDO>> result = AsyncUtil.runTasksWithResult(IntStream.range(0, page).mapToObj(index -> (Callable<List<KickScrewItemDO>>) () -> {
                KickScrewAlgoliaRequest newRequest = new KickScrewAlgoliaRequest();
                BeanUtils.copyProperties(algoliaRequest, newRequest);
                newRequest.setPageIndex(index);
                return kickScrewClient.queryItemByBrandV2(newRequest);
            }).toList());
            AsyncUtil.runTasks(List.of(() -> batchInsertItems(result.stream().flatMap(List::stream).toList())));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void batchInsertItems(List<KickScrewItemDO> items) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            for (KickScrewItemDO item : items) {
                kickScrewItemMapper.insertIgnore(item);
            }
            sqlSession.commit();
        }
        log.info("batchInsertItems success");
    }

    @Override
    public List<String> selectItemsByCondition() {
        List<KickScrewItemDO> kickScrewItemDOS = kickScrewItemMapper.selectModelNoByCondition(new KickScrewItemRequest());
        return null;
    }

    @Override
    public void refreshAllPrices() {
        kickScrewPriceMapper.delete(new QueryWrapper<>());
        long currentTimeMillis = System.currentTimeMillis();

        KickScrewItemRequest kickScrewItemRequest = new KickScrewItemRequest();
        Integer count = kickScrewItemMapper.count(new KickScrewItemRequest());
        int page = (int) Math.ceil(count / 1000.0);
        kickScrewItemRequest.setPageSize(1000);
        for (int i = 1; i <= page; i++) {
            long pageStart = System.currentTimeMillis();
            kickScrewItemRequest.setPageIndex(i);
            List<KickScrewItemDO> itemDOList = kickScrewItemMapper.selectModelNoByCondition(kickScrewItemRequest);
            CountDownLatch latch = new CountDownLatch(itemDOList.size());
            List<KickScrewPriceDO> toInsert = new CopyOnWriteArrayList<>();
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
            try {
                latch.await();
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
            Thread.ofVirtual().start(() -> kickScrewPriceMapper.insert(toInsert));
            log.info("page refresh end, cost:{}, pageIndex:{}", System.currentTimeMillis() - pageStart, i);
        }
        log.info("refreshAllPrices end, cost:{}", System.currentTimeMillis() - currentTimeMillis);
    }

    @Override
    public void changePrice() {
        // 1.查询kc商品价格
        long count = kickScrewPriceMapper.count();
        long startIndex = 0;
        while (startIndex < count) {
            try {
                List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewPriceMapper.selectPage(startIndex, 1000);
                // todo:查询这些货号&尺码在读物的价格
                List<KickScrewUploadItem> toUpload = new ArrayList<>();
                for (KickScrewPriceDO kickScrewPriceDO : kickScrewPriceDOS) {
                    KickScrewUploadItem kickScrewUploadItem = new KickScrewUploadItem();
                    kickScrewUploadItem.setModel_no(kickScrewPriceDO.getModelNo());
                    kickScrewUploadItem.setSize(kickScrewPriceDO.getEuSize());
                    kickScrewUploadItem.setSize_system("EU");
                    kickScrewUploadItem.setQty(1);
                    kickScrewUploadItem.setPrice(ShoesUtil.getPrice(1, 2));
                    toUpload.add(kickScrewUploadItem);
                }
                AsyncUtil.runTasks(List.of(() -> kickScrewClient.batchUploadItems(toUpload)));
                startIndex += 1000;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }
}
