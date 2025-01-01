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
    }

    public void scratchAndSaveItems() {
//        scratchAndSaveCategories();
//        List<BrandDO> brandDOList = brandMapper.selectList(new QueryWrapper<>());
        BrandDO brandDO1 = new BrandDO();
        brandDO1.setName("ANTA");
        brandDO1.setCnt(30);
        BrandDO brandDO2 = new BrandDO();
        brandDO2.setName("NIKE");
        brandDO2.setCnt(60);
        List<BrandDO> brandDOList = List.of(brandDO1, brandDO2);
        for (BrandDO brandDO : brandDOList) {
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
        }
    }
}
