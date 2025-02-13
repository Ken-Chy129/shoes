package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.mapper.ItemMapper;
import cn.ken.shoes.mapper.ItemSizePriceMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.*;
import cn.ken.shoes.model.price.PriceRequest;
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

    public Result<List<ItemDO>> queryPriceByCondition(PriceRequest priceRequest) {
        List<ItemDO> result = new ArrayList<>();
        PriceEnum priceType = PriceEnum.from(priceRequest.getPriceType());
        String brand = priceRequest.getBrand();

        return Result.buildSuccess(result);
    }

    public void scratchAndSaveItems() {
//        Map<String, Integer> brandSizes = KickScrewContext.brandSizes;
        Map<String, Integer> brandSizes = Map.of("ANTA", 30);
        // 1.遍历所有品牌
        for (Map.Entry<String, Integer> entry : brandSizes.entrySet()) {
            String brand = entry.getKey();
            Integer total = entry.getValue();
            // 根据品牌的商品数量计算请求的分页次数
            int page = (int) Math.ceil(total / (double) KickScrewConfig.PAGE_SIZE);
            List<KickScrewItemDO> brandItems = new ArrayList<>();
            // 2.查询品牌下所有商品
            for (int i = 1; i <= page; i++) {
                brandItems.addAll(kickScrewClient.queryItemByBrand(brand, i));
            }
            // 3.保存商品+价格信息
//            for (KickScrewItemDO kickScrewItemDO : brandItems) {
//                // 创建ItemDO
//                ItemDO itemDO = new ItemDO();
//                itemDO.setModelNumber(kickScrewItemDO.getModelNo());
//                itemDO.setImage(kickScrewItemDO.getImage());
//                itemDO.setBrandName(kickScrewItemDO.getBrand());
//                itemDO.setProductType(kickScrewItemDO.getProductType());
//                String modelNumber = kickScrewItemDO.getModelNo();
//                PoisonItem poisonItem = poisonClient.queryItemByModelNos(modelNumber);
//                itemDO.setName(poisonItem.getTitle());
//                itemMapper.insert(itemDO);
//                // 查询商品不同尺码的价格
//                List<ItemSizePriceDO> itemSizePriceDOS = new ArrayList<>();
//                // 查询kc价格
//                String handle = kickScrewItemDO.getHandle();
//                List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(handle);
//                kickScrewSizePrices.stream()
//                        .map(this::toSizePrice)
//                        .forEach(itemSizePriceDO -> {
//                            itemSizePriceDO.setModelNumber(modelNumber);
//                            itemSizePriceDOS.add(itemSizePriceDO);
//                        });
//                // 查询得物价格
//                for (Sku sku : poisonItem.getSkus()) {
//                    Long skuId = sku.getSkuId();
//                    String size = JSON.parseObject(sku.getProperties()).getString("尺码");
//                    ItemSizePriceDO itemSizePriceDO = itemSizePriceDOS.stream().filter(itemSizePrice -> size.equals(itemSizePrice.getEuSize())).findFirst().orElse(null);
//                    if (itemSizePriceDO == null) {
//                        continue;
//                    }
//                    itemSizePriceDO.setModelNumber(modelNumber);
//                    itemSizePriceDO.setSkuId(skuId);
//                    itemSizePriceDO.setEuSize(size);
//                    Integer fastPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.FAST);
//                    Integer normalPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.NORMAL);
//                    Integer lightningPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.LIGHTNING);
//                    Optional.ofNullable(fastPrice).ifPresent(price -> itemSizePriceDO.setPoisonFastPrice(BigDecimal.valueOf(price)));
//                    Optional.ofNullable(normalPrice).ifPresent(price -> itemSizePriceDO.setPoisonNormalPrice(BigDecimal.valueOf(price)));
//                    Optional.ofNullable(lightningPrice).ifPresent(price -> itemSizePriceDO.setPoisonLightningPrice(BigDecimal.valueOf(price)));
//                    itemSizePriceDOS.add(itemSizePriceDO);
//                }
//                System.out.println(JSON.toJSONString(itemSizePriceDOS));
//                itemSizePriceMapper.insert(itemSizePriceDOS);
//            }
        }
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
                            Map<String, Map<PriceEnum, Integer>> sizePriceMap = poisonClient.queryPriceBySpu(spuId);
                            if (sizePriceMap == null) {
                                return;
                            }
                            for (Map.Entry<String, Map<PriceEnum, Integer>> entry : sizePriceMap.entrySet()) {
                                String size = entry.getKey();
                                Map<PriceEnum, Integer> priceMap = entry.getValue();
                                if (priceMap == null) {
                                    continue;
                                }
                                PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
                                poisonPriceDO.setModelNo(articleNumber);
                                poisonPriceDO.setEuSize(size);
                                poisonPriceDO.setNormalPrice(priceMap.get(PriceEnum.NORMAL));
                                poisonPriceDO.setLightningPrice(priceMap.get(PriceEnum.NORMAL));
                                toInsert.add(poisonPriceDO);
                            }
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

}
