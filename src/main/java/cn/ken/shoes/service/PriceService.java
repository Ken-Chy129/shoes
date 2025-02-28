package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.mapper.*;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.price.PriceRequest;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

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
    private ItemMapper itemMapper;

    @Resource
    private ItemSizePriceMapper itemSizePriceMapper;

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
}
