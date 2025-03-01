package cn.ken.shoes.service;

import cn.ken.shoes.annotation.Task;
import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;

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
    private SizeChartMapper sizeChartMapper;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    @Resource
    private KickScrewPriceMapper kickScrewPriceMapper;

    /**
     * 查询品牌性别尺码映射表
     */
    public void queryBrandGenderSizeMap() {
        List<String> genderList = ItemQueryConfig.ALL_GENDER;
        KickScrewCategory kickScrewCategory = kickScrewClient.queryBrand();
        Map<String, Integer> brandCntMap = kickScrewCategory.getBrand();
        for (String brand : brandCntMap.keySet()) {
            for (String gender : genderList) {
                KickScrewAlgoliaRequest kickScrewAlgoliaRequest = new KickScrewAlgoliaRequest();
                kickScrewAlgoliaRequest.setBrands(List.of(brand));
                kickScrewAlgoliaRequest.setGenders(List.of(gender));
                kickScrewAlgoliaRequest.setPageIndex(0);
                kickScrewAlgoliaRequest.setPageSize(1);
                List<KickScrewItemDO> itemList = kickScrewClient.queryItemPageV2(kickScrewAlgoliaRequest);
                if (CollectionUtils.isEmpty(itemList)) {
                    continue;
                }
                KickScrewItemDO kickScrewItemDO = itemList.getFirst();
                String modelNo = kickScrewItemDO.getModelNo();
                List<Map<String, String>> sizeChart = kickScrewClient.queryItemSizeChart(brand, modelNo);
                List<SizeChartDO> sizeChartDOS = new ArrayList<>();
                for (Map<String, String> sizeMap : sizeChart) {
                    SizeChartDO sizeChartDO = new SizeChartDO();
                    sizeChartDO.setBrand(brand);
                    sizeChartDO.setGender(gender);
                    sizeChartDO.setEuSize(sizeMap.get(SizeEnum.EU.getCode()));
                    sizeChartDO.setMenUSSize(sizeMap.get(SizeEnum.MEN_US.getCode()));
                    sizeChartDO.setWomenUSSize(sizeMap.get(SizeEnum.WOMAN_US.getCode()));
                    sizeChartDO.setUsSize(Optional.ofNullable(sizeMap.get(SizeEnum.US.getCode()))
                            .orElse(Optional.ofNullable(sizeChartDO.getMenUSSize())
                                    .orElse(sizeChartDO.getWomenUSSize())
                            )
                    );
                    sizeChartDO.setUkSize(sizeMap.get(SizeEnum.UK.getCode()));
                    sizeChartDO.setCmSize(sizeMap.get(SizeEnum.CM.getCode()));
                    sizeChartDOS.add(sizeChartDO);
                }
                Thread.ofVirtual().start(() -> sizeChartMapper.insert(sizeChartDOS));
            }
        }
    }

    public void refreshBrand() {
        KickScrewCategory kickScrewCategory = kickScrewClient.queryBrand();
        if (kickScrewCategory == null || kickScrewCategory.getBrand() == null) {
            log.error("scratchAndSaveBrand|queryBrand no result");
            return;
        }
        Map<String, Integer> brandCntMap = kickScrewCategory.getBrand();
        List<BrandDO> brandDOList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : brandCntMap.entrySet()) {
            BrandDO brandDO = new BrandDO();
            brandDO.setName(entry.getKey());
            brandDO.setTotal(entry.getValue());
            brandDO.setCrawlCnt(entry.getValue());
            brandDO.setNeedCrawl(true);
            brandDOList.add(brandDO);
        }
        brandMapper.batchInsertOrUpdate(brandDOList);
    }

    public List<String> queryMustCrawlModelNos() {
        return mustCrawlMapper.queryByPlatformList("kc");
    }

    public void updateMustCrawlModelNos(List<String> modelNoList) {
        mustCrawlMapper.deleteByPlatform("kc");
        List<MustCrawlDO> mustCrawlDOList = new ArrayList<>();
        for (String modelNo : modelNoList) {
            MustCrawlDO mustCrawlDO = new MustCrawlDO();
            mustCrawlDO.setPlatform("kc");
            mustCrawlDO.setModelNo(modelNo);
            mustCrawlDOList.add(mustCrawlDO);
        }
        mustCrawlMapper.insert(mustCrawlDOList);
    }

    public void updateDefaultCrawlCnt(Integer cnt) {
        brandMapper.updateDefaultCrawlCnt(cnt);
    }

    @Task
    public void refreshHotItems() {
        List<BrandDO> brandDOList = brandMapper.selectList(new QueryWrapper<>());
        kickScrewItemMapper.deleteAll();
        for (BrandDO brandDO : brandDOList) {
            Boolean needCrawl = brandDO.getNeedCrawl();
            if (Boolean.FALSE.equals(needCrawl)) {
                continue;
            }
            String brandName = brandDO.getName();
            Integer crawlCnt = brandDO.getCrawlCnt();
            int page = (int) Math.ceil(crawlCnt / 50.0);
            for (int i = 0; i < page; i++) {
                KickScrewAlgoliaRequest request = new KickScrewAlgoliaRequest();
                request.setBrands(List.of(brandName));
                request.setPageIndex(i);
                request.setPageSize(50);
                List<KickScrewItemDO> kickScrewItemDOS = kickScrewClient.queryItemPageV2(request);
                Thread.ofVirtual().start(() -> kickScrewItemMapper.insert(kickScrewItemDOS));
            }
        }
    }

    /**
     * 指定货号刷新价格
     */
    public void refreshPricesByModelNos(List<String> modelNoList) {
        kickScrewPriceMapper.delete(new QueryWrapper<>());
        for (List<String> modelNos : Lists.partition(modelNoList, 100)) {
            List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(modelNos);
            Thread.ofVirtual().start(() -> kickScrewPriceMapper.insert(kickScrewPriceDOS));
        }
    }

    public void refreshPrices() {
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        List<String> mustCrawlModelNos = mustCrawlMapper.queryByPlatformList("kc");
        hotModelNos.addAll(mustCrawlModelNos);
        List<String> modelNos = hotModelNos.stream().distinct().toList();
        refreshPricesByModelNos(modelNos);
    }

    @Task
    public void refreshItems() {
        // 1.爬取品牌和商品数量
        refreshBrand();
        // 2.根据配置爬取指定品牌和数量的热门商品
        refreshHotItems();
    }
}
