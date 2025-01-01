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

@Slf4j
@Service
public class KickScrewService {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    public void scratchAndSaveCategories() {
        long startTime = System.currentTimeMillis();
        log.info("scratchAndSaveCategories start");
        KickScrewCategory kickScrewCategory = kickScrewClient.queryCategory();
        if (kickScrewCategory == null || kickScrewCategory.getBrand() == null) {
            log.error("queryCategory no result");
            return;
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
        log.info("scratchAndSaveCategories end, cnt:{}, cost:{}", brandDOList.size(), System.currentTimeMillis() - startTime);
    }

    public void scratchAndSaveItems() {
        kickScrewItemMapper.delete(new QueryWrapper<>());

        scratchAndSaveCategories();
        List<BrandDO> brandDOList = brandMapper.selectList(new QueryWrapper<>());

        long allStartTime = System.currentTimeMillis();
        log.info("scratchAndSaveItems start, brand count:{}", brandDOList.size());
        for (BrandDO brandDO : brandDOList) {
            log.info("start brand:{}, size:{}", brandDO.getName(), brandDO.getCnt());
            long brandStartTime = System.currentTimeMillis();
            Thread.ofVirtual().start(() -> {
                String brand = brandDO.getName();
                Integer cnt = brandDO.getCnt();
                // 根据品牌的商品数量计算请求的分页次数
                int page = (int) Math.ceil(cnt / (double) KickScrewConfig.PAGE_SIZE);
                // 查询品牌下所有商品
                for (int i = 1; i <= page; i++) {
                    final int pageIndex = i;
                    Thread.ofVirtual().start(() -> {
                        List<KickScrewItemDO> brandItems = kickScrewClient.queryItemByBrand(brand, pageIndex);
                        kickScrewItemMapper.insert(brandItems);
                    });
                }
            });
            log.info("end brand:{}, cost:{}", brandDO.getName(), System.currentTimeMillis() - brandStartTime);
        }
        log.info("scratchAndSaveItems end, cost:{}", System.currentTimeMillis() - allStartTime);
    }
}
