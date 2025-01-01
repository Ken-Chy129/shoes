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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

@Slf4j
@Service
public class KickScrewService {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

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
        brandMapper.delete(new QueryWrapper<>());
        brandMapper.insert(brandDOList);
        int itemCnt = brandCntMap.values().stream().mapToInt(Integer::intValue).sum();
        log.info("scratchAndSaveCategories end, categoryCnt:{}, itemCnt:{}, cost:{}",
                brandDOList.size(),
                itemCnt,
                System.currentTimeMillis() - startTime);
        return itemCnt;
    }

    public void scratchAndSaveItems() {
        kickScrewItemMapper.delete(new QueryWrapper<>());

        int itemCnt = scratchAndSaveCategories();
        List<BrandDO> brandDOList = brandMapper.selectList(new QueryWrapper<>());

        long allStartTime = System.currentTimeMillis();
        log.info("scratchAndSaveItems start, brandCnt:{}, itemCnt:{}", brandDOList.size(), itemCnt);
        CountDownLatch brandLatch = new CountDownLatch(brandDOList.size());
        for (BrandDO brandDO : brandDOList) {
            Thread.ofVirtual().start(() -> {
                log.info("start brand:{}, size:{}", brandDO.getName(), brandDO.getCnt());
                long brandStartTime = System.currentTimeMillis();
                String brand = brandDO.getName();
                Integer cnt = brandDO.getCnt();
                // 根据品牌的商品数量计算请求的分页次数
                int page = (int) Math.ceil(cnt / (double) KickScrewConfig.PAGE_SIZE);
                CountDownLatch pageLatch = new CountDownLatch(page);
                // 查询品牌下所有商品
                for (int i = 1; i <= page; i++) {
                    final int pageIndex = i;
                    Thread.ofVirtual().name("brandItems-" + brand).start(() -> {
                        List<KickScrewItemDO> brandItems = kickScrewClient.queryItemByBrand(brand, pageIndex);
                        kickScrewItemMapper.insert(brandItems);
                        pageLatch.countDown();
                        log.info("finish insert Items, brand:{}, page:{}", brand, pageIndex);
                    });
                }
                try {
                    pageLatch.await();
                } catch (InterruptedException e) {
                    log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brand, e.getMessage());
                }
                brandLatch.countDown();
                log.info("end brand:{}, cost:{}", brandDO.getName(), System.currentTimeMillis() - brandStartTime);
            });
        }
        try {
            brandLatch.await();
        } catch (InterruptedException e) {
            log.error("scratchAndSaveItems error, msg:{}", e.getMessage());
        }
        log.info("scratchAndSaveItems end, cost:{}", System.currentTimeMillis() - allStartTime);
    }
}
