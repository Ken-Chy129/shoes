package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.model.kickscrew.KickScrewUploadItem;
import cn.ken.shoes.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

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

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    private static final Integer PAGE_SIZE = 1_000;

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

    @SneakyThrows
    public void refreshHotItems(boolean clearOld) {
        List<BrandDO> brandDOList = brandMapper.selectList(new QueryWrapper<>());
        if (clearOld) {
            kickScrewItemMapper.deleteAll();
        }
        for (BrandDO brandDO : brandDOList) {
            Boolean needCrawl = brandDO.getNeedCrawl();
            if (Boolean.FALSE.equals(needCrawl)) {
                continue;
            }
            String brandName = brandDO.getName();
            Integer crawlCnt = brandDO.getCrawlCnt();
            int page = (int) Math.ceil(crawlCnt / 50.0);
            CountDownLatch latch = new CountDownLatch(page);
            for (int i = 0; i < page; i++) {
                final int pageIndex = i;
                Thread.ofVirtual().name("refreshHotItems-" + brandName).start(() -> {
                    try {
                        LimiterHelper.limitKcItem();
                        KickScrewAlgoliaRequest request = new KickScrewAlgoliaRequest();
                        request.setBrands(List.of(brandName));
                        request.setPageIndex(pageIndex);
                        request.setPageSize(50);
                        List<KickScrewItemDO> kickScrewItemDOS = kickScrewClient.queryItemPageV2(request);
                        SqlHelper.batch(kickScrewItemDOS, item -> kickScrewItemMapper.insertIgnore(item));
                    } catch (Exception e) {
                        log.error("refreshHotItems error, msg:{}", e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
    }

    /**
     * 指定货号刷新价格
     */
    @SneakyThrows
    public void refreshPricesByModelNos(List<String> modelNoList) {
        kickScrewPriceMapper.delete(new QueryWrapper<>());
        List<List<String>> partition = Lists.partition(modelNoList, 60);
        for (List<String> modelNos : partition) {
            List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(modelNos);
            SqlHelper.batch(kickScrewPriceDOS, price -> kickScrewPriceMapper.insert(price));
        }
    }

    public void refreshPrices() {
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        List<String> mustCrawlModelNos = mustCrawlMapper.queryByPlatformList("kc");
        hotModelNos.addAll(mustCrawlModelNos);
        List<String> modelNos = hotModelNos.stream().distinct().toList();
        refreshPricesByModelNos(modelNos);
    }

    public void refreshItems(boolean clearOld) {
        // 1.爬取品牌和商品数量
        refreshBrand();
        // 2.根据配置爬取指定品牌和数量的热门商品
        refreshHotItems(clearOld);
    }

    public int compareWithPoisonAndChangePrice() {
        int uploadCnt = 0;
        kickScrewClient.deleteAllItems();
        long count = poisonPriceMapper.count();
        long startIndex = 0;
        while (startIndex < count) {
            try {
                // 1.查询得物价格
                List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectPage(startIndex, PAGE_SIZE);
                Set<String> modelNos = poisonPriceDOList.stream().map(PoisonPriceDO::getModelNo).collect(Collectors.toSet());
                // 2.查询对应的货号在kc的价格
                Map<String, Integer> kcPriceMap = kickScrewPriceMapper.selectListByModelNos(modelNos).stream()
                        .collect(Collectors.toMap(
                                kcPrice -> kcPrice.getModelNo() + ":" + kcPrice.getEuSize(),
                                KickScrewPriceDO::getPrice,
                                (k1, k2) -> k1
                        ));
                List<KickScrewUploadItem> toUpload = new ArrayList<>();
                for (PoisonPriceDO poisonPriceDO : poisonPriceDOList) {
                    String modelNo = poisonPriceDO.getModelNo();
                    String euSize = poisonPriceDO.getEuSize();
                    Integer kcPrice = kcPriceMap.get(modelNo + ":" + euSize);
                    if (kcPrice == null || poisonPriceDO.getPrice() == null) {
                        continue;
                    }
                    boolean canEarn = ShoesUtil.canEarn(poisonPriceDO.getPrice(), kcPrice);
                    if (!canEarn) {
                        continue;
                    }
                    KickScrewUploadItem kickScrewUploadItem = new KickScrewUploadItem();
                    kickScrewUploadItem.setModel_no(modelNo);
                    kickScrewUploadItem.setSize(euSize);
                    kickScrewUploadItem.setSize_system("EU");
                    kickScrewUploadItem.setQty(1);
                    kickScrewUploadItem.setPrice(kcPrice - 1);
                    toUpload.add(kickScrewUploadItem);
                }
                uploadCnt += toUpload.size();
                kickScrewClient.batchUploadItems(toUpload);
                startIndex += PAGE_SIZE;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        return uploadCnt;
    }
}
