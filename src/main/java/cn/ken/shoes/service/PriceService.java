package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.price.PriceRequest;
import cn.ken.shoes.model.price.PriceVO;
import cn.ken.shoes.util.ShoesUtil;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceService {

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private KickScrewPriceMapper kickScrewPriceMapper;

    @Resource
    private TaskService taskService;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    @Resource
    private ItemService kickScrewItemService;

    public Result<List<ItemDO>> queryPriceByCondition(PriceRequest priceRequest) {
        List<ItemDO> result = new ArrayList<>();
        PriceEnum priceType = PriceEnum.from(priceRequest.getPriceType());
        String brand = priceRequest.getBrand();

        return Result.buildSuccess(result);
    }

    public void refreshPoisonPrices() {
        Long taskId = taskService.startTask("poison", TaskDO.TaskTypeEnum.REFRESH_PRICES, null);
        poisonPriceMapper.delete(null);
        int count = poisonItemMapper.count();
        int page = (int) Math.ceil(count / 1000.0);
        RateLimiter limiter = RateLimiter.create(10);
        for (int i = 1; i <= page; i++) {
            try {
                long start = System.currentTimeMillis();
                List<PoisonItemDO> poisonItemDOS = poisonItemMapper.selectSpuId((i - 1) * page, 1000);
                List<PoisonPriceDO> toInsert = new CopyOnWriteArrayList<>();
                CountDownLatch latch = new CountDownLatch(poisonItemDOS.size());
                for (PoisonItemDO poisonItemDO : poisonItemDOS) {
                    limiter.acquire();
                    Thread.ofVirtual().start(() -> {
                        try {
                            Long spuId = poisonItemDO.getSpuId();
                            String articleNumber = poisonItemDO.getArticleNumber();
                            List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpu(articleNumber, spuId);
                            if (poisonPriceDOList.isEmpty()) {
                                return;
                            }
                            toInsert.addAll(poisonPriceDOList);
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                Thread.ofVirtual().start(() -> poisonPriceMapper.insert(toInsert));
                log.info("refreshPoisonPrices finish, page:{}, cost:{}", page, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        taskService.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS);
    }


    public void refreshKcPrices() {
        List<KickScrewItemDO> kickScrewItemDOS = kickScrewItemMapper.selectAllItemsWithPoisonPrice();
        log.info("refreshKcPrices cnt:{}", kickScrewItemDOS.size());
        kickScrewItemService.refreshPrices(kickScrewItemDOS.stream().map(KickScrewItemDO::getModelNo).toList());
    }

    public Result<List<PriceVO>> queryByModelNoFromDB(String modelNo) {
        PoisonItemDO poisonItemDO = poisonItemMapper.selectByArticleNumber(modelNo);
        // 商品不存在
        if (poisonItemDO == null) {
            return Result.buildError("数据库中不存在该得物货号");
        }
        List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectListByModelNos(Set.of(modelNo));
        if (CollectionUtils.isEmpty(poisonPriceDOList)) {
            return Result.buildError("数据库中不存在该货号的得物价格");
        }
        // 2 查询kc价格
        Map<String, Integer> kcPriceMap = kickScrewPriceMapper.selectListByModelNos(Set.of(modelNo))
                .stream()
                .collect(Collectors.toMap(
                        KickScrewPriceDO::getEuSize,
                        KickScrewPriceDO::getPrice
                ));
        if (MapUtils.isEmpty(kcPriceMap)) {
            return Result.buildError("数据库中不存在该货号的kc价格");
        }
        return fillResult(poisonPriceDOList, kcPriceMap);
    }

    public Result<List<PriceVO>> queryByModelNoRealTime(String modelNo) {
        // 1.查询商品
        List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(List.of(modelNo));
        if (CollectionUtils.isEmpty(poisonItemDOS)) {
            return Result.buildError("得物不存在该商品");
        }
        // 2.查询价格
        PoisonItemDO poisonItemDO = poisonItemDOS.getFirst();
        List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpuV2(modelNo, poisonItemDO.getSpuId());
        if (CollectionUtils.isEmpty(poisonPriceDOList)) {
            return Result.buildError("得物价格不存在");
        }
        // 3.查询kc价格
        List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(List.of(modelNo));
        Map<String, Integer> kcPriceMap = kickScrewPriceDOS.stream()
                .collect(Collectors.toMap(
                        KickScrewPriceDO::getEuSize,
                        KickScrewPriceDO::getPrice
                ));
        // 4.补全其他价格
        return fillResult(poisonPriceDOList, kcPriceMap);
    }

    public Result<List<PriceVO>> queryByModelNoDbFirst(String modelNo) {
        PoisonItemDO poisonItemDO = poisonItemMapper.selectByArticleNumber(modelNo);
        // 商品不存在
        if (poisonItemDO == null) {
            // 1.查询商品
            List<PoisonItemDO> poisonItemDOS = poisonClient.queryItemByModelNos(List.of(modelNo));
            if (CollectionUtils.isEmpty(poisonItemDOS)) {
                return Result.buildError("得物不存在该商品");
            }
            // 2.保存商品信息
            poisonItemDO = poisonItemDOS.getFirst();
            poisonItemMapper.insert(poisonItemDO);
            // 3.查询价格
            List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpuV2(modelNo, poisonItemDO.getSpuId());
            if (CollectionUtils.isEmpty(poisonPriceDOList)) {
                return Result.buildError("得物价格不存在");
            }
            // 4.保存价格
            poisonPriceMapper.insert(poisonPriceDOList);
        }
        // 1.查询得物价格
        List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectListByModelNos(Set.of(modelNo));
        if (CollectionUtils.isEmpty(poisonPriceDOList)) {
            poisonPriceDOList = poisonClient.queryPriceBySpuV2(modelNo, poisonItemDO.getSpuId());
            if (CollectionUtils.isEmpty(poisonPriceDOList)) {
                return Result.buildError("得物价格不存在");
            }
        }

        // 2.查询kc价格
        Map<String, Integer> kcPriceMap = kickScrewPriceMapper.selectListByModelNos(Set.of(modelNo))
                .stream()
                .collect(Collectors.toMap(
                        KickScrewPriceDO::getEuSize,
                        KickScrewPriceDO::getPrice
                ));
        if (MapUtils.isEmpty(kcPriceMap)) {
            List<KickScrewPriceDO> kickScrewPriceDOS = kickScrewClient.queryLowestPrice(List.of(modelNo));
            if (CollectionUtils.isNotEmpty(kickScrewPriceDOS)) {
                kickScrewPriceMapper.insert(kickScrewPriceDOS);
                kcPriceMap = kickScrewPriceDOS.stream()
                        .collect(Collectors.toMap(
                                KickScrewPriceDO::getEuSize,
                                KickScrewPriceDO::getPrice
                        ));
            }
        }

        // 4.补全其他价格
        return fillResult(poisonPriceDOList, kcPriceMap);
    }

    private Result<List<PriceVO>> fillResult(List<PoisonPriceDO> poisonPriceDOList, Map<String, Integer> kcPriceMap) {
        List<PriceVO> result = new ArrayList<>();
        for (PoisonPriceDO poisonPriceDO : poisonPriceDOList) {
            PriceVO priceVO = PriceVO.build(poisonPriceDO);
            String euSize = poisonPriceDO.getEuSize();
            // 补全kc价格
            Integer kcPrice = kcPriceMap.get(euSize);
            priceVO.setKcPrice(kcPrice * PriceSwitch.EXCHANGE_RATE);
            double kcEarn = ShoesUtil.getKcEarn(poisonPriceDO.getPrice(), kcPrice);
            priceVO.setKcEarn(BigDecimal.valueOf(kcEarn).setScale(2, RoundingMode.DOWN).doubleValue());
            // 补全绿叉价格
            result.add(priceVO);
        }
        return Result.buildSuccess(result);
    }

}
