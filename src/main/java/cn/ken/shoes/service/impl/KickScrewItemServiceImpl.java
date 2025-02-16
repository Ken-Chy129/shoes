package cn.ken.shoes.service.impl;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.config.ItemQueryConfig;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewItemRequest;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.kickscrew.KickScrewUploadItem;
import cn.ken.shoes.service.ItemService;
import cn.ken.shoes.service.TaskService;
import cn.ken.shoes.util.AsyncUtil;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.TimeUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service("kickScrewItemService")
public class KickScrewItemServiceImpl implements ItemService {

    private static final Integer PAGE_SIZE = 10_000;

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

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private TaskService taskService;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Override
    public String getPlatformName() {
        return "Kickscrew";
    }

    @Override
    public List<BrandDO> scratchBrands() {
        return List.of();
    }

    public List<BrandDO> selectBrands() {
        return brandMapper.selectList(new QueryWrapper<>());
    }

    @Override
    public void refreshAllItems() {
        Long taskId = taskService.startTask(getPlatformName(), TaskDO.TaskTypeEnum.REFRESH_ALL_ITEMS, null);
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
                    Integer page = kickScrewClient.countItemPageV2(algoliaRequest);
                    CountDownLatch pageLatch = new CountDownLatch(page);
                    for (int i = 0; i < page; i++) {
                        final int pageIndex = i;
                        Thread.ofVirtual().name(brand + ":" + pageIndex).start(() -> {
                            try {
                                algoliaRequest.setPageIndex(pageIndex);
                                List<KickScrewItemDO> brandItems = kickScrewClient.queryItemPageV2(algoliaRequest);
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
                Thread.ofVirtual().start(() -> {
                    batchInsertItems(releaseYearItemsMap.values().stream().flatMap(List::stream).toList());
                    taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS);
                });
            } catch (Exception e) {
                taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED);
                log.error("scratchAndSaveBrandItems error, brand:{}, msg:{}", brand, e.getMessage());
            } finally {
                log.info("finishScratch brand:{}, idx:{}, cnt:{}, cost:{}", brand, finishCnt.incrementAndGet(), brandItemCnt, TimeUtil.getCostMin(brandStartTime));
            }
        }
    }

    @Override
    public void refreshIncrementalItems() {
        Long taskId = taskService.startTask(getPlatformName(), TaskDO.TaskTypeEnum.REFRESH_INCREMENTAL_ITEMS, null);
        try {
            Integer recentYear = ItemQueryConfig.ALL_RELEASE_YEARS.getFirst();
            // 查询品牌下所有商品
            KickScrewAlgoliaRequest algoliaRequest = new KickScrewAlgoliaRequest();
            algoliaRequest.setReleaseYears(List.of(recentYear));
            Integer page = kickScrewClient.countItemPageV2(algoliaRequest);
            List<List<KickScrewItemDO>> result = AsyncUtil.runTasksWithResult(IntStream.range(0, page).mapToObj(index -> (Callable<List<KickScrewItemDO>>) () -> {
                KickScrewAlgoliaRequest newRequest = new KickScrewAlgoliaRequest();
                BeanUtils.copyProperties(algoliaRequest, newRequest);
                newRequest.setPageIndex(index);
                return kickScrewClient.queryItemPageV2(newRequest);
            }).toList());
            List<KickScrewItemDO> incrementalItems = result.stream().flatMap(List::stream).toList();
            AsyncUtil.runTasks(List.of(() -> batchInsertItems(incrementalItems)));
            log.info("finish refreshIncrementalItems, incrementalItems cnt:{}", incrementalItems.size());
        } catch (Exception e) {
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED);
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
    }

    @Override
    public List<String> selectItemsByCondition() {
        List<KickScrewItemDO> kickScrewItemDOS = kickScrewItemMapper.selectModelNoByCondition(new KickScrewItemRequest());
        return null;
    }

    @Override
    public void refreshAllPrices() {
        Long taskId = taskService.startTask(getPlatformName(), TaskDO.TaskTypeEnum.REFRESH_PRICES, null);
        try {
            kickScrewPriceMapper.delete(new QueryWrapper<>());
            long startTime = System.currentTimeMillis();

            KickScrewItemRequest kickScrewItemRequest = new KickScrewItemRequest();
            Integer count = kickScrewItemMapper.count(new KickScrewItemRequest());
            int page = (int) Math.ceil((double) count / PAGE_SIZE);
            log.info("refreshAllPrices start, count:{}, page:{}", count, page);
            kickScrewItemRequest.setPageSize(PAGE_SIZE);
            RateLimiter rateLimiter = RateLimiter.create(50);
            ReentrantLock lock = new ReentrantLock();
            for (int i = 1; i <= page; i++) {
                try {
                    long pageStart = System.currentTimeMillis();
                    kickScrewItemRequest.setPageIndex(i);
                    List<KickScrewItemDO> itemDOList = kickScrewItemMapper.selectModelNoByCondition(kickScrewItemRequest);
                    CountDownLatch latch = new CountDownLatch(itemDOList.size());
                    List<KickScrewPriceDO> toInsert = new CopyOnWriteArrayList<>();
                    for (KickScrewItemDO itemDO : itemDOList) {
                        Thread.ofVirtual().name("refreshAllPrices:" + itemDO.getModelNo()).start(() -> {
                            try {
                                rateLimiter.acquire();
                                List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(itemDO.getHandle());
                                String modelNo = itemDO.getModelNo();
                                for (KickScrewSizePrice kickScrewSizePrice : kickScrewSizePrices) {
                                    String title = kickScrewSizePrice.getTitle();
                                    String euSize = ShoesUtil.getEuSizeFromKickScrew(title);
                                    if (euSize == null) {
                                        log.error("kcPrice error, modelNo:{}, title:{}", modelNo, title);
                                        continue;
                                    }
                                    KickScrewPriceDO kickScrewPriceDO = new KickScrewPriceDO();
                                    kickScrewPriceDO.setModelNo(modelNo);
                                    kickScrewPriceDO.setEuSize(euSize);
                                    Map<String, String> price = kickScrewSizePrice.getPrice();
                                    kickScrewPriceDO.setPrice(kickScrewSizePrice.isAvailableForSale() ? (int) Double.parseDouble(String.valueOf(price.get("amount"))) : -1);
                                    toInsert.add(kickScrewPriceDO);
                                }
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            } finally {
                                latch.countDown();
                            }
                        });
                    }
                    latch.await();
                    Thread.ofVirtual().start(() -> {
                        lock.lock();
                        kickScrewPriceMapper.insert(toInsert);
                        lock.unlock();
                    });
                    log.info("page refresh end, cost:{}, pageIndex:{}, cnt:{}", TimeUtil.getCostMin(pageStart), i, toInsert.size());
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("refreshAllPrices end, cost:{}", TimeUtil.getCostMin(startTime));
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS);
        } catch (Exception e) {
            log.error("refreshAllPrices error, msg:{}", e.getMessage());
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED);
        }
    }

    public void refreshAllPricesV2() {
        Long taskId = taskService.startTask(getPlatformName(), TaskDO.TaskTypeEnum.REFRESH_PRICES, null);
        try {
            kickScrewPriceMapper.delete(new QueryWrapper<>());
            long startTime = System.currentTimeMillis();

            Long count = kickScrewItemMapper.countItemsWithPoisonPrice();
            int page = (int) Math.ceil((double) count / PAGE_SIZE);
            log.info("refreshAllPrices start, count:{}, page:{}", count, page);
            RateLimiter rateLimiter = RateLimiter.create(50);
            ReentrantLock lock = new ReentrantLock();
            for (int i = 1; i <= page; i++) {
                try {
                    long pageStart = System.currentTimeMillis();
                    List<KickScrewItemDO> itemDOList = kickScrewItemMapper.selectItemsWithPoisonPrice((i - 1) * PAGE_SIZE, PAGE_SIZE);
                    CountDownLatch latch = new CountDownLatch(itemDOList.size());
                    List<KickScrewPriceDO> toInsert = new CopyOnWriteArrayList<>();
                    for (KickScrewItemDO itemDO : itemDOList) {
                        Thread.ofVirtual().name("refreshAllPrices:" + itemDO.getModelNo()).start(() -> {
                            try {
                                rateLimiter.acquire();
                                List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(itemDO.getHandle());
                                String modelNo = itemDO.getModelNo();
                                for (KickScrewSizePrice kickScrewSizePrice : kickScrewSizePrices) {
                                    String title = kickScrewSizePrice.getTitle();
                                    String euSize = ShoesUtil.getEuSizeFromKickScrew(title);
                                    if (euSize == null) {
                                        log.error("kcPrice error, modelNo:{}, title:{}", modelNo, title);
                                        continue;
                                    }
                                    KickScrewPriceDO kickScrewPriceDO = new KickScrewPriceDO();
                                    kickScrewPriceDO.setModelNo(modelNo);
                                    kickScrewPriceDO.setEuSize(euSize);
                                    Map<String, String> price = kickScrewSizePrice.getPrice();
                                    kickScrewPriceDO.setPrice(kickScrewSizePrice.isAvailableForSale() ? (int) Double.parseDouble(String.valueOf(price.get("amount"))) : -1);
                                    toInsert.add(kickScrewPriceDO);
                                }
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            } finally {
                                latch.countDown();
                            }
                        });
                    }
                    latch.await();
                    Thread.ofVirtual().start(() -> {
                        lock.lock();
                        kickScrewPriceMapper.insert(toInsert);
                        lock.unlock();
                    });
                    log.info("page refresh end, cost:{}, pageIndex:{}, cnt:{}", TimeUtil.getCostMin(pageStart), i, toInsert.size());
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("refreshAllPrices end, cost:{}", TimeUtil.getCostMin(startTime));
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS);
        } catch (Exception e) {
            log.error("refreshAllPrices error, msg:{}", e.getMessage());
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED);
        }
    }

    @Override
    public void compareWithPoisonAndChangePrice() {
        Long taskId = taskService.startTask(getPlatformName(), TaskDO.TaskTypeEnum.CHANGE_PRICES, null);
        try {
            long count = poisonPriceMapper.count();
            log.info("compareWithPoisonAndChangePrice start, count:{}", count);
            long startIndex = 0;
            while (startIndex < count) {
                try {
                    long startTime = System.currentTimeMillis();
                    // 1.查询kc价格
                    List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectPage(startIndex, PAGE_SIZE);
                    Set<String> modelNos = poisonPriceDOList.stream().map(PoisonPriceDO::getModelNo).collect(Collectors.toSet());
                    // 2.查询对应的货号在得物的价格
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
                        if (kcPrice == null) {
                            continue;
                        }
                        if (PoisonSwitch.POISON_PRICE_TYPE == 0 && poisonPriceDO.getNormalPrice() == null) {
                            continue;
                        }
                        if (PoisonSwitch.POISON_PRICE_TYPE == 1 && poisonPriceDO.getLightningPrice() == null) {
                            continue;
                        }
                        boolean canEarn = ShoesUtil.canEarn(PoisonSwitch.POISON_PRICE_TYPE == 0 ? poisonPriceDO.getNormalPrice() : poisonPriceDO.getLightningPrice(), kcPrice);
                        log.info("compareWithKc, modelNumber:{}, size:{}, poisonPrice:{}, kcPrice:{}, canEarn:{}", modelNo, euSize,
                                PoisonSwitch.POISON_PRICE_TYPE == 0 ? poisonPriceDO.getNormalPrice() : poisonPriceDO.getLightningPrice(), kcPrice, canEarn);
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
                    AsyncUtil.runTasks(List.of(() -> kickScrewClient.batchUploadItems(toUpload)));
                    log.info("compareWithPoisonAndChangePrice end, pageIndex:{}, cnt:{}, cost:{}", startIndex, count, TimeUtil.getCostMin(startTime));
                    startIndex += PAGE_SIZE;
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS);
        } catch (Exception e) {
            log.error("compareWithPoisonAndChangePrice error, msg:{}", e.getMessage());
            taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.FAILED);
        }
    }
}
