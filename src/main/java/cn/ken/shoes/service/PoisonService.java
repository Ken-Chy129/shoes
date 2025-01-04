package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.entity.PoisonItemDO;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    public void refreshPoisonItems() {
//        List<String> brandList = brandMapper.selectBrandNames();
        List<String> brandList = List.of("Gucci", "ANTA", "CASIO");
        RateLimiter rateLimiter = RateLimiter.create(15);
        int total = 0;
        for (String brand : brandList) {
            List<String> brandModelNoList = kickScrewItemMapper.selectModelNoByBrand(brand);
            // 已存在的商品不需要再查询
            List<String> existBrandModelNoList = poisonItemMapper.selectModelNoByKcBrand(brand);
            brandModelNoList.removeAll(existBrandModelNoList);
            System.out.println(JSON.toJSONString(brandModelNoList));
            List<PoisonItemDO> brandItems = new CopyOnWriteArrayList<>();
            for (List<String> fiveModelNoList : Lists.partition(brandModelNoList, 5)) {
                double waitTime = rateLimiter.acquire();
                log.info("waitTime:{}", waitTime);
                Thread.ofVirtual().name("poison-api").start(() -> {
                    List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(fiveModelNoList);
                    if (CollectionUtils.isEmpty(poisonItemDOS)) {
                        return;
                    }
                    if (poisonItemDOS.size() < fiveModelNoList.size()) {
                        log.warn("result less than request, request:{}, result:{}",
                            String.join(",", fiveModelNoList),
                            String.join(",", poisonItemDOS.stream().map(PoisonItemDO::getArticleNumber).toList())
                        );
                    }
                    brandItems.addAll(poisonItemDOS);
                });
            }
            for (PoisonItemDO brandItem : brandItems) {
                brandItem.setKcBrand(brand);
            }
            Thread.ofVirtual().name("sql").start(() -> poisonItemMapper.insert(brandItems));
            total += brandItems.size();
            log.info("refreshPoisonItems, brand:{}, cnt:{}", brand, brandItems.size());
        }
        log.info("refreshPoisonItems, total:{}", total);
    }
}
