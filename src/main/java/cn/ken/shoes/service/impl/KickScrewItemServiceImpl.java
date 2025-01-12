package cn.ken.shoes.service.impl;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.service.ItemService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class KickScrewItemServiceImpl implements ItemService {

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;
    
    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    @Override
    public List<BrandDO> scratchBrands() {
        return List.of();
    }

    @Override
    public void scratchItems() {
        kickScrewItemMapper.deleteAll();

        List<BrandDO> brandList = scratchBrands();
        
        AtomicInteger finishCnt = new AtomicInteger(0);
        for (BrandDO brandDO : brandList) {
            String brand = brandDO.getName();
            long brandStartTime = System.currentTimeMillis();
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
                Thread.ofVirtual().start(() -> batchInsertItems(releaseYearItemsMap));
            } catch (Exception e) {
                log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brand, e.getMessage());
            } finally {
                log.info("finishScratch brand:{}, idx:{}, cnt:{}, cost:{}", brand, finishCnt.incrementAndGet(), brand, System.currentTimeMillis() - brandStartTime);
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
    public List<ItemDO> selectItemsByCondition() {
        return List.of();
    }

    @Override
    public void scratchPrices(List<ItemDO> items) {

    }

    @Override
    public void changePrice() {
        
    }
}
