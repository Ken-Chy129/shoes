package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.entity.PoisonItemDO;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    public void refreshPoisonItems() {
//        List<String> brandList = brandMapper.selectBrandNames();
        List<String> brandList = List.of("Gucci", "ANTA", "CASIO");
        RateLimiter rateLimiter = RateLimiter.create(15);
        for (String brand : brandList) {
            List<String> brandModelNoList = kickScrewItemMapper.selectModelNoByBrand(brand);
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
            Thread.ofVirtual().name("sql").start(() -> poisonItemMapper.insert(brandItems));
        }
    }
}
