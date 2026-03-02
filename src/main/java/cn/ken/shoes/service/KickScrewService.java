package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.manager.PriceManager;
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

import java.io.*;
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
    private PriceManager priceManager;

    @Resource
    private ShoesService shoesService;

    @Resource
    private TaskItemMapper taskItemMapper;

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
            brandDO.setPlatform("kc");
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
        if (CollectionUtils.isEmpty(modelNoList)) {
            return;
        }
        List<MustCrawlDO> mustCrawlDOList = new ArrayList<>();
        for (String modelNo : modelNoList) {
            MustCrawlDO mustCrawlDO = new MustCrawlDO();
            mustCrawlDO.setPlatform("kc");
            mustCrawlDO.setModelNo(modelNo);
            mustCrawlDOList.add(mustCrawlDO);
        }
        mustCrawlMapper.insert(mustCrawlDOList);
    }

    @SneakyThrows
    public void queryHotModels() {
        List<String> brandDOList = List.of("361 Degrees", "HOKA ONE ONE", "ANTA", "Li-Ning", "Rigorer", "Onitsuka Tiger");
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("file/货号.txt")));
        for (String brandName : brandDOList) {
            int crawlCnt = 500;
            int page = (int) Math.ceil(crawlCnt / 50.0);
            CountDownLatch latch = new CountDownLatch(page);
            for (int i = 0; i < page; i++) {
                final int pageIndex = i;
                Thread.ofVirtual().name("queryHotModel-" + brandName).start(() -> {
                    try {
                        LimiterHelper.limitKcItem();
                        KickScrewAlgoliaRequest request = new KickScrewAlgoliaRequest();
                        request.setBrands(List.of(brandName));
                        request.setPageIndex(pageIndex);
                        request.setPageSize(50);
                        List<KickScrewItemDO> kickScrewItemDOS = kickScrewClient.queryItemPageV2(request);
                        kickScrewItemDOS.stream().map(KickScrewItemDO::getModelNo).forEach(modelName -> {
                            try {
                                writer.write(modelName);
                                writer.newLine();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    } catch (Exception e) {
                        log.error("queryHotModel error, msg:{}", e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
        writer.close();
    }

    @SneakyThrows
    public void refreshHotItems() {
        List<BrandDO> brandDOList = brandMapper.selectByPlatform("kc");
        kickScrewItemMapper.deleteAll();
        for (BrandDO brandDO : brandDOList) {
            Boolean needCrawl = brandDO.getNeedCrawl();
            if (Boolean.FALSE.equals(needCrawl)) {
                continue;
            }
            String brandName = brandDO.getName();
            Integer crawlCnt = Math.min(brandDO.getCrawlCnt(), brandDO.getTotal());
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

    @SneakyThrows
    public void updateItems() {
        long time = System.currentTimeMillis();
        List<BrandDO> brandDOList = brandMapper.selectByPlatform("kc");
        int brandCnt = 0;
        for (BrandDO brandDO : brandDOList) {
            try {
                String brandName = brandDO.getName();
                List<KickScrewItemDO> kickScrewItemDOS = kickScrewClient.searchItems(brandName);
                if (!kickScrewItemDOS.isEmpty()) {
                    brandCnt++;
                }
                int successCnt = SqlHelper.batchWithResult(kickScrewItemDOS, item -> kickScrewItemMapper.insertIgnore(item));
                log.info("updateItems, brand:{}, cnt:{}, newItemCnt:{}", brandName, kickScrewItemDOS.size(), successCnt);
            } catch (Exception e) {
                log.error("updateItems error, msg:{}", e.getMessage());
            }
        }
        log.info("updateItems finish, allBrandCnt:{}, brandWithResultCnt:{}, cost:{}", brandDOList.size(), brandCnt, TimeUtil.getCostMin(time));
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
            SqlHelper.batch(kickScrewPriceDOS, price -> kickScrewPriceMapper.insertIgnore(price));
        }
    }

    public void listing() {
        long time = System.currentTimeMillis();
        Long taskId = TaskSwitch.CURRENT_KC_LISTING_TASK_ID;
        int round = TaskSwitch.CURRENT_KC_LISTING_ROUND;

        // 1.爬取品牌和商品数量
        refreshBrand();
        // 2.根据配置爬取指定品牌和数量的热门商品
        refreshHotItems();
        log.info("upload get item, cost:{}", TimeUtil.getCostMin(time));
        // 清空kc价格
        kickScrewPriceMapper.delete(new QueryWrapper<>());
        // 查询要比价的货号
        List<String> modelNoList = queryAllModels();
        List<List<String>> partition = Lists.partition(modelNoList, 60);
        int uploadCnt = 0;
        // 查询价格并上架盈利商品
        for (List<String> modelNos : partition) {
            // 检查暂停或取消状态
            if (TaskSwitch.CANCEL_KC_LISTING_TASK) {
                log.info("KC任务已暂停或取消，终止执行");
                return;
            }
            List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(modelNos);
            Thread.startVirtualThread(() -> SqlHelper.batch(kickScrewPriceDOS, price -> kickScrewPriceMapper.insertIgnore(price)));

            // 为每个货号+尺码创建 TaskItem 记录
            Map<String, Long> priceKeyToTaskItemIdMap = new HashMap<>();
            if (taskId != null) {
                for (KickScrewPriceDO priceDO : kickScrewPriceDOS) {
                    TaskItemDO taskItemDO = new TaskItemDO();
                    taskItemDO.setTaskId(taskId);
                    taskItemDO.setRound(round);
                    taskItemDO.setStyleId(priceDO.getModelNo());
                    taskItemDO.setEuSize(priceDO.getEuSize());
                    taskItemDO.setCurrentPrice(priceDO.getPrice() != null ? java.math.BigDecimal.valueOf(priceDO.getPrice()) : null);
                    taskItemDO.setOperateTime(new Date());
                    taskItemDO.setOperateResult("待处理");
                    taskItemMapper.insert(taskItemDO);
                    String key = priceDO.getModelNo() + "_" + priceDO.getEuSize();
                    priceKeyToTaskItemIdMap.put(key, taskItemDO.getId());
                }
            }

            uploadCnt += compareWithPoisonAndChangePrice(kickScrewPriceDOS, priceKeyToTaskItemIdMap);
        }
        log.info("upload finish, uploadCnt:{}, cost:{}", uploadCnt, TimeUtil.getCostMin(time));
    }

    public List<String> queryAllModels() {
        List<String> result = new ArrayList<>();
        // kc热门商品
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        result.addAll(hotModelNos);
        // 必爬商品
        List<String> mustCrawlModelNos = mustCrawlMapper.selectAllModelNos();
        result.addAll(mustCrawlModelNos);
        result = result.stream().distinct().collect(Collectors.toList());
        // 移除瑕疵商品
        result.removeIf(ShoesContext::isFlawsModel);
        // 移除无价商品
        result.removeIf(ShoesContext::isNoPrice);
        log.info("queryAllModels, hotModelSize:{}, mustCrawlModelSize:{}, flawsModelSize:{}, noPriceModelSize:{}, finalModelSize:{}",
                hotModelNos.size(),
                mustCrawlModelNos.size(),
                ShoesContext.getFlawsModelSet().size(),
                ShoesContext.getNoPriceModelSet().size(),
                result.size()
        );
        return result;
    }

    public void priceDown() {
        long time = System.currentTimeMillis();
        // 检查暂停或取消状态
        if (TaskSwitch.CANCEL_KC_LISTING_TASK) {
            return;
        }
        // 下架不盈利的商品
        int deleteCnt = clearNoBenefitItem();
        kickScrewClient.autoMatch();
        log.info("priceDown finish, deleteCnt: {}, cost:{}", deleteCnt, TimeUtil.getCostMin(time));
    }

    public int compareWithPoisonAndChangePrice(List<KickScrewPriceDO> kickScrewPriceDOS, Map<String, Long> priceKeyToTaskItemIdMap) {
        List<KickScrewUploadItem> toUpload = new ArrayList<>();
        List<Long> uploadTaskItemIds = new ArrayList<>();
        // 按原因分类的跳过记录
        List<Long> noPoisonPriceIds = new ArrayList<>();
        List<Long> exceedMaxPriceIds = new ArrayList<>();
        List<Long> noKcPriceIds = new ArrayList<>();
        List<Long> noProfitIds = new ArrayList<>();

        try {
            Set<String> modelNos = kickScrewPriceDOS.stream().map(KickScrewPriceDO::getModelNo).collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(modelNos)) {
                return 0;
            }
            // 先遍历缓存查询有哪些货号没有价格，没有的批量调用接口查询一次，并更新缓存（查询后没有的货号设置空缓存，避免每次重新查询）
            priceManager.preloadMissingPrices(modelNos);

            for (KickScrewPriceDO kickScrewPriceDO : kickScrewPriceDOS) {
                // 检查暂停或取消状态
                if (TaskSwitch.CANCEL_KC_LISTING_TASK) {
                    log.info("KC任务已暂停或取消，终止执行");
                    return toUpload.size();
                }
                String modelNo = kickScrewPriceDO.getModelNo();
                String euSize = kickScrewPriceDO.getEuSize();
                String key = modelNo + "_" + euSize;
                Long taskItemId = priceKeyToTaskItemIdMap.get(key);

                Integer poisonPrice = priceManager.getPoisonPrice(modelNo, euSize);

                // 更新 TaskItem 的得物价格信息
                if (taskItemId != null && poisonPrice != null) {
                    TaskItemDO updateItem = new TaskItemDO();
                    updateItem.setId(taskItemId);
                    updateItem.setPoisonPrice(java.math.BigDecimal.valueOf(poisonPrice));
                    taskItemMapper.updateById(updateItem);
                }

                if (poisonPrice == null) {
                    if (taskItemId != null) {
                        noPoisonPriceIds.add(taskItemId);
                    }
                    continue;
                }
                if (poisonPrice > PoisonSwitch.MAX_PRICE) {
                    if (taskItemId != null) {
                        exceedMaxPriceIds.add(taskItemId);
                    }
                    continue;
                }
                if (kickScrewPriceDO.getPrice() == null) {
                    if (taskItemId != null) {
                        noKcPriceIds.add(taskItemId);
                    }
                    continue;
                }
                Integer minExpectProfit = ShoesUtil.isThreeFiveModel(modelNo, euSize) ? PoisonSwitch.MIN_THREE_PROFIT : PoisonSwitch.MIN_PROFIT;
                boolean canEarn = ShoesUtil.canKcEarn(poisonPrice, kickScrewPriceDO.getPrice(), minExpectProfit);
                if (!canEarn) {
                    if (taskItemId != null) {
                        noProfitIds.add(taskItemId);
                    }
                    continue;
                }
                KickScrewUploadItem kickScrewUploadItem = new KickScrewUploadItem();
                kickScrewUploadItem.setModel_no(modelNo);
                kickScrewUploadItem.setSize(euSize);
                kickScrewUploadItem.setSize_system("EU");
                kickScrewUploadItem.setQty(1);
                kickScrewUploadItem.setPrice(kickScrewPriceDO.getPrice() - 1);
                toUpload.add(kickScrewUploadItem);
                if (taskItemId != null) {
                    uploadTaskItemIds.add(taskItemId);
                }
            }

            // 更新跳过记录的状态（按原因分类）
            if (!noPoisonPriceIds.isEmpty()) {
                taskItemMapper.batchUpdateResult(noPoisonPriceIds, "无须上架-无得物价格");
            }
            if (!exceedMaxPriceIds.isEmpty()) {
                taskItemMapper.batchUpdateResult(exceedMaxPriceIds, "无须上架-超过最高价");
            }
            if (!noKcPriceIds.isEmpty()) {
                taskItemMapper.batchUpdateResult(noKcPriceIds, "无须上架-无KC价格");
            }
            if (!noProfitIds.isEmpty()) {
                taskItemMapper.batchUpdateResult(noProfitIds, "无须上架-无盈利");
            }

            if (toUpload.isEmpty()) {
                return 0;
            }
            // 检查暂停或取消状态，跳过上架操作
            if (TaskSwitch.CANCEL_KC_LISTING_TASK) {
                log.info("KC任务已暂停或取消，跳过上架操作");
                return 0;
            }
            kickScrewClient.batchUploadItems(toUpload);

            // 更新上架成功的记录状态
            if (!uploadTaskItemIds.isEmpty()) {
                taskItemMapper.batchUpdateResult(uploadTaskItemIds, "上架成功");
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return toUpload.size();
    }


    public int clearNoBenefitItem() {
        int deleteCnt = 0;
        int total = kickScrewClient.queryListingsTotal();
        int pageSize = 10000;
        int pages = (int) Math.ceil(total / (double) pageSize);
        Long taskId = TaskSwitch.CURRENT_KC_PRICE_DOWN_TASK_ID;
        int round = TaskSwitch.CURRENT_KC_PRICE_DOWN_ROUND;

        for (int i = 0; i < pages; i++) {
            // 检查暂停或取消状态
            if (TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK) {
                log.info("KC压价任务已暂停或取消，终止下架操作");
                return deleteCnt;
            }
            List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryListings(i * pageSize, pageSize);
            if (CollectionUtils.isEmpty(kickScrewPriceDOS)) {
                continue;
            }

            // 为所有商品创建 TaskItem 记录，初始状态为"待处理"
            Map<KickScrewPriceDO, Long> priceToTaskItemIdMap = new HashMap<>();
            if (taskId != null) {
                for (KickScrewPriceDO kickScrewPriceDO : kickScrewPriceDOS) {
                    String modelNo = kickScrewPriceDO.getModelNo();
                    String euSize = kickScrewPriceDO.getEuSize();
                    Integer price = kickScrewPriceDO.getPrice();
                    Integer poisonPrice = priceManager.getPoisonPrice(modelNo, euSize);

                    TaskItemDO taskItemDO = new TaskItemDO();
                    taskItemDO.setTaskId(taskId);
                    taskItemDO.setRound(round);
                    taskItemDO.setStyleId(modelNo);
                    taskItemDO.setEuSize(euSize);
                    taskItemDO.setCurrentPrice(price != null ? java.math.BigDecimal.valueOf(price) : null);
                    taskItemDO.setPoisonPrice(poisonPrice != null ? java.math.BigDecimal.valueOf(poisonPrice) : null);
                    taskItemDO.setOperateTime(new Date());
                    taskItemDO.setOperateResult("待处理");
                    taskItemMapper.insert(taskItemDO);
                    priceToTaskItemIdMap.put(kickScrewPriceDO, taskItemDO.getId());
                }
            }

            List<KickScrewPriceDO> toDelete = new ArrayList<>();
            List<Long> noNeedDeleteIds = new ArrayList<>();

            for (KickScrewPriceDO kickScrewPriceDO : kickScrewPriceDOS) {
                // 检查暂停或取消状态
                if (TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK) {
                    log.info("KC压价任务已暂停或取消，终止下架操作");
                    return deleteCnt;
                }
                String modelNo = kickScrewPriceDO.getModelNo();
                String euSize = kickScrewPriceDO.getEuSize();
                if (ShoesContext.isNotCompareModel(modelNo, euSize)) {
                    // 不压价下架的商品
                    Long taskItemId = priceToTaskItemIdMap.get(kickScrewPriceDO);
                    if (taskItemId != null) {
                        noNeedDeleteIds.add(taskItemId);
                    }
                    continue;
                }
                Integer price = kickScrewPriceDO.getPrice();
                Integer poisonPrice = priceManager.getPoisonPrice(modelNo, euSize);
                Integer minExpectProfit = ShoesUtil.isThreeFiveModel(modelNo, euSize) ? PoisonSwitch.MIN_THREE_PROFIT : PoisonSwitch.MIN_PROFIT;
                if (poisonPrice == null || !ShoesUtil.canKcEarn(poisonPrice, price + 1, minExpectProfit)) {
                    // 得物无价或无盈利，下架该商品
                    toDelete.add(kickScrewPriceDO);
                } else {
                    // 有盈利，无须下架
                    Long taskItemId = priceToTaskItemIdMap.get(kickScrewPriceDO);
                    if (taskItemId != null) {
                        noNeedDeleteIds.add(taskItemId);
                    }
                }
            }

            // 检查暂停或取消状态，跳过删除操作
            if (TaskSwitch.CANCEL_KC_PRICE_DOWN_TASK) {
                log.info("KC压价任务已暂停或取消，跳过删除操作");
                return deleteCnt;
            }

            deleteCnt += toDelete.size();
            kickScrewClient.deleteList(toDelete);

            // 更新 TaskItem 状态
            if (taskId != null) {
                // 下架成功的商品
                List<Long> deleteSuccessIds = toDelete.stream()
                        .map(priceToTaskItemIdMap::get)
                        .filter(Objects::nonNull)
                        .toList();
                if (!deleteSuccessIds.isEmpty()) {
                    taskItemMapper.batchUpdateResult(deleteSuccessIds, "下架成功");
                }
                // 无须下架的商品
                if (!noNeedDeleteIds.isEmpty()) {
                    taskItemMapper.batchUpdateResult(noNeedDeleteIds, "无须下架");
                }
            }
        }
        return deleteCnt;
    }
}
